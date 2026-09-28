package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val SETTINGS_ENTRY = "Lapp/hushmessenger/extension/SettingsEntry;"

/**
 * The in-app Messenger settings entry is intentionally separate from launcher shortcuts.
 *
 * This finder is conservative: it only accepts a row tap/selection method that already carries
 * Messenger settings semantics. If the supported 580 build changes, patching must stop instead of
 * producing a build whose README says "Open Messenger settings" but has no entry there.
 */
internal fun findSettingsEntryHooks(classes: Iterable<ClassDef>): List<Method> {
    val candidates = mutableListOf<Method>()
    for (cls in classes) for (method in cls.methods) {
        val instructions = method.implementation?.instructions?.toList() ?: continue
        val strings = instructions.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()
        if (!method.parameterTypes.any { it == "Landroid/view/View;" || it == "Landroid/content/Context;" }) continue
        if (method.returnType !in setOf("V", "Z")) continue
        val lower = strings.map { it.lowercase() }
        val looksLikeSettings = lower.any { it.contains("settings") || it.contains("preferences") || it.contains("account") }
        val looksLikeRow = lower.any { it.contains("row") || it.contains("item") || it.contains("cell") }
        if (looksLikeSettings && looksLikeRow) candidates.add(method)
    }
    return candidates
}

internal fun validateSettingsEntryHooks(found: List<Method>) {
    if (found.size != 1) {
        throw PatchException(
            "Messenger settings entry: expected one in-app settings row hook but found ${found.size}. " +
                "Keep the embedded SettingsActivity and skip the native settings row hook until the Messenger 580 settings row hook is verified.",
        )
    }
}

internal fun MutableMethod.injectSettingsEntry() {
    validateScratch()
    val returnCode = when (returnType) {
        "V" -> "return-void"
        "Z" -> "const/4 v0, 0x1\nreturn v0"
        else -> throw PatchException("Messenger settings entry: unsupported hook return type $returnType")
    }
    fun injectWithContext(contextRegister: String) {
        addInstructionsWithLabels(0, """
            invoke-static {$contextRegister}, $SETTINGS_ENTRY->open(Landroid/content/Context;)Z
            move-result v0
            if-eqz v0, :stock_behavior
            $returnCode
        """.trimIndent(), ExternalLabel("stock_behavior", getInstruction(0)))
    }
    fun injectWithView(viewRegister: String) {
        addInstructionsWithLabels(0, """
            invoke-virtual {$viewRegister}, Landroid/view/View;->getContext()Landroid/content/Context;
            move-result-object v0
            invoke-static {v0}, $SETTINGS_ENTRY->open(Landroid/content/Context;)Z
            move-result v0
            if-eqz v0, :stock_behavior
            $returnCode
        """.trimIndent(), ExternalLabel("stock_behavior", getInstruction(0)))
    }
    when {
        !AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.firstOrNull() == "Landroid/content/Context;" -> injectWithContext("p1")
        !AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.firstOrNull() == "Landroid/view/View;" -> injectWithView("p1")
        AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.firstOrNull() == "Landroid/content/Context;" -> injectWithContext("p0")
        AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.firstOrNull() == "Landroid/view/View;" -> injectWithView("p0")
        else -> throw PatchException("Messenger settings entry: unsupported hook parameter layout")
    }
}


internal fun MutableMethod.injectSettingsEntryInstaller() {
    fun installWithContext(contextRegister: String) {
        addInstructionsWithLabels(0, """
            invoke-static {$contextRegister}, $SETTINGS_ENTRY->install(Landroid/content/Context;)V
        """.trimIndent())
    }
    fun installWithView(viewRegister: String) {
        addInstructionsWithLabels(0, """
            invoke-static {$viewRegister}, $SETTINGS_ENTRY->install(Landroid/view/View;)V
        """.trimIndent())
    }
    when {
        !AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.firstOrNull() == "Landroid/content/Context;" -> installWithContext("p1")
        !AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.firstOrNull() == "Landroid/view/View;" -> installWithView("p1")
        AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.firstOrNull() == "Landroid/content/Context;" -> installWithContext("p0")
        AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.firstOrNull() == "Landroid/view/View;" -> installWithView("p0")
    }
}
