package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val TEXT_STYLE = "text_style"
internal const val TEXT_STYLE_ACTIVITY = "app.hushmessenger.extension.TextStyleActivity"
/** The selection menu's two entries and their labels. The extension tells them apart by these names. */
internal val TEXT_STYLE_ENTRIES = listOf(
    "app.hushmessenger.extension.BoldText" to "Bold",
    "app.hushmessenger.extension.ItalicText" to "Italic",
)

/** Litho's text input names this when changed text makes it measure again. No other EditText does, in any tested build. */
internal const val TEXT_INPUT_REMEASURE = "updateState:TextInputComponent.remeasureForUpdatedText"
internal const val EDIT_TEXT = "Landroid/widget/EditText;"
private const val TEXT_VIEW = "Landroid/widget/TextView;"
private const val TEXT_CHANGED = "onTextChanged(Ljava/lang/CharSequence;III)V"
private const val FOCUS_CHANGED = "onFocusChanged(ZILandroid/graphics/Rect;)V"
internal const val TEXT_FIELD_FOCUSED = "Lapp/hushmessenger/extension/TextStyleActivity;->textFieldFocused(Landroid/view/View;)V"

private fun textFieldChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the chat text field changed ($detail)")

/** Name, parameters and return type, the part of a hook id that doesn't change between builds. */
private fun MethodReference.signature() = "$name(${parameterTypes.joinToString("")})$returnType"

private fun Method.namesRemeasure() = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == TEXT_INPUT_REMEASURE
} == true

/** Index of the focus change's one call to Android's own, made on `this`. Null when that isn't the shape. */
internal fun Method.focusSuperCall(): Int? {
    if (AccessFlags.STATIC.isSet(accessFlags) || signature() != FOCUS_CHANGED || implementation == null) return null
    val code = implementation!!.instructions.toList()
    val self = parameterRegister(0)
    return code.indices.filter { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        val receiver = (code[at] as? FiveRegisterInstruction)?.registerC ?: (code[at] as? RegisterRangeInstruction)?.startRegister
        (code[at].opcode == Opcode.INVOKE_SUPER || code[at].opcode == Opcode.INVOKE_SUPER_RANGE) && call != null &&
            call.definingClass in setOf(TEXT_VIEW, EDIT_TEXT) && call.signature() == FOCUS_CHANGED && receiver == self
    }.singleOrNull()
}

/**
 * Messenger's Litho text input, the chat box among them, is an EditText with no view id, and Android adds PROCESS_TEXT
 * entries (Bold and Italic, and every other app's) only to a field that has one, so the chat box never showed them
 * (#42). The input is the one EditText whose onTextChanged names its remeasure, and the hook is that class's own
 * onFocusChanged. A Litho spec names the remeasure too, but it isn't a view. Nothing unless there's exactly one of each.
 */
internal fun findTextStyleField(classes: Iterable<ClassDef>): List<Method> {
    val anchors = classes.filter { it.superclass == EDIT_TEXT }
        .flatMap { cls -> cls.methods.filter { it.namesRemeasure() }.map { cls to it } }
    val (field, anchor) = anchors.singleOrNull() ?: return emptyList()
    if (AccessFlags.STATIC.isSet(anchor.accessFlags) || anchor.signature() != TEXT_CHANGED) return emptyList()
    return field.methods.filter { it.focusSuperCall() != null }.takeIf { it.size == 1 }.orEmpty()
}

internal fun MutableMethod.validateTextStyleField() {
    if (focusSuperCall() == null) textFieldChanged("no single focus change call on this in ${hookId()}")
}

/**
 * The text input hands itself to the extension first on every focus change. While Bold and Italic is in effect, a field
 * without an id gets one there, before Android builds its selection menu. Off, Pause and safe mode leave it alone.
 */
internal fun MutableMethod.injectTextStyleField() {
    validateTextStyleField()
    addInstructions(0, "invoke-static {p0}, $TEXT_FIELD_FOCUSED")
}

/**
 * Declares the screen behind Bold and Italic and its two selection menu entries (#42). Android lists every enabled
 * PROCESS_TEXT activity of an app in that app's own text fields, exported or not, so the entries start disabled and the
 * extension enables them only while the switch is in effect.
 */
internal fun Document.addTextStyle() {
    val application = getElementsByTagName("application").item(0) as? Element
        ?: throw PatchException("Messenger controls: expected one application")
    fun Element.child(tag: String, vararg attributes: Pair<String, String>): Element = createElement(tag).also { node ->
        attributes.forEach { (name, value) -> node.setAttribute("android:$name", value) }
        appendChild(node)
    }
    application.child("activity", "name" to TEXT_STYLE_ACTIVITY, "exported" to "false", "excludeFromRecents" to "true",
        "theme" to "@android:style/Theme.Translucent.NoTitleBar")
    // An alias has to come after the activity it points at.
    for ((name, label) in TEXT_STYLE_ENTRIES) {
        val filter = application.child("activity-alias", "name" to name, "targetActivity" to TEXT_STYLE_ACTIVITY,
            "label" to label, "exported" to "false", "enabled" to "false").child("intent-filter")
        filter.child("action", "name" to "android.intent.action.PROCESS_TEXT")
        filter.child("category", "name" to "android.intent.category.DEFAULT")
        filter.child("data", "mimeType" to "text/plain")
    }
}
