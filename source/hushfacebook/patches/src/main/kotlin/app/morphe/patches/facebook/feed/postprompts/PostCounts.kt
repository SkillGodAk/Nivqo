/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The numbers on a post's Like, Comment and Share buttons (#180). Read from 583, 2026-10-17, arm64
 * and armv7; the obfuscated names here are for reviewers, the code never writes one down.
 *
 * - One static builder turns a button's count into its text, (Resources, Integer, int) -> String
 *   (LX/2jc;->A04 on both builds). A null or non-positive count takes Facebook's own early answer,
 *   const-string "" and return-object, and the button then shows just its word, as on a post nobody
 *   has reacted to. Any other count goes through a number formatter and getQuantityString.
 * - Its callers are the button row's text (LX/3BT;->A0G), the footer item builder (LX/3B0;->A01), a
 *   lambda, a sibling builder (LX/2jc;->A03) and the Share button plugin, a kept class name. The
 *   reaction summary line above the buttons (BlingBarComponent) never calls it, so it keeps its
 *   numbers (#158).
 * - The hook goes first thing in the builder, with v0 free there (six locals, nothing jumps to the
 *   first instruction), and answers the same empty text while the switch is on.
 */

/** The Share button plugin. Its class name is kept, since Facebook's plugin registry names it by its Java path. */
internal const val REPOST_BUTTON_PLUGIN =
    "Lcom/facebook/feedback/feedufi/plugins/footeritem/repostbutton/RepostButtonFeedUFIFooterItemPlugin;"

internal const val RESOURCES = "Landroid/content/res/Resources;"
internal const val QUANTITY_STRING = "$RESOURCES->getQuantityString(II[Ljava/lang/Object;)Ljava/lang/String;"
internal const val HIDE_POST_COUNTS = "$POST_PROMPTS->hidePostCounts()Z"

/** The count builder's shape: (Resources, Integer, int) -> String. */
private val COUNT_TEXT_PARAMETERS = listOf(RESOURCES, "Ljava/lang/Integer;", "I")

private fun MethodReference.signature(): String =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** Whether [reference] has the count builder's shape. */
internal fun isCountTextShape(reference: MethodReference): Boolean =
    reference.returnType == "Ljava/lang/String;" &&
        reference.parameterTypes.map(CharSequence::toString) == COUNT_TEXT_PARAMETERS

/** The distinct static calls of the count builder's shape in [plugin]'s methods. */
internal fun countTextCalls(plugin: ClassDef): List<MethodReference> =
    plugin.methods.flatMap { method ->
        method.implementation?.instructions?.filter { it.opcode == Opcode.INVOKE_STATIC }
            ?.mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            ?.filter(::isCountTextShape).orEmpty()
    }.distinctBy { it.signature() }

/**
 * Whether [method] is the count builder: static, with Facebook's empty answer for no count and a
 * plural lookup for the rest.
 */
internal fun isCountTextBuilder(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && holdsString(method, "") &&
        method.implementation?.instructions?.any {
            it.opcode == Opcode.INVOKE_VIRTUAL &&
                ((it as ReferenceInstruction).reference as? MethodReference)?.signature() == QUANTITY_STRING
        } == true

/**
 * Asks the extension first thing and answers the builder's own empty text when it says so. At the
 * first instruction no local holds anything yet, so v0 is free there.
 */
internal fun MutableMethod.hideCountsWhen(hook: String) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :facebooks_count
            const-string v0, ""
            return-object v0
        """,
        ExternalLabel("facebooks_count", getInstruction(0)),
    )
}

/**
 * Finds the count builder through the Share button plugin, holds it to its empty answer and plural
 * lookup, and has it answer the empty text while the switch is on. Refuses unless each step finds one.
 */
internal fun BytecodePatchContext.hidePostCounts() {
    val plugin = classDefByOrNull(REPOST_BUTTON_PLUGIN)
        ?: throw PatchException("$PATCH: $REPOST_BUTTON_PLUGIN isn't in this build")
    val calls = countTextCalls(plugin)
    val call = calls.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one static (Resources, Integer, int) -> String call in ${plugin.type}, found ${calls.size}",
    )
    val method = mutableClassDefBy(call.definingClass).methods.singleOrNull {
        it.name == call.name && it.returnType == call.returnType &&
            it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)
    } ?: throw PatchException("$PATCH: ${call.signature()} has no body in this build")
    if (!isCountTextBuilder(method)) {
        throw PatchException("$PATCH: ${call.signature()} isn't a static builder with an empty answer and a plural lookup")
    }
    method.hideCountsWhen(HIDE_POST_COUNTS)
}
