package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** The inbox search bar's "Ask Meta AI or search" hint. */
internal const val AI_SEARCH_HINT = "ai_search_hint"

/** The test tag the inbox search bar gives its field. */
internal const val SEARCH_BAR_TAG = "search_bar"

internal const val AI_SEARCH_HINT_HELPER = "$SETTINGS->metaAiSearchHint(Z)Z"
private const val GET_STRING = "Landroid/content/res/Resources;->getString(I)Ljava/lang/String;"

private fun hintChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the inbox search hint moved ($detail)")

/**
 * Index of the move-result that holds the Meta AI search check. The search bar builder picks its hint like this:
 *
 *     invoke-virtual {vC, vSession}, <check>(FbUserSession)Z
 *     move-result vH                                   <- true: "Ask Meta AI or search"
 *     invoke-virtual {vX}, <context>()Resources
 *     move-result-object vRes
 *     const vR, <plain hint>
 *     if-eqz vH, :pick
 *     const vR, <Meta AI hint>
 *     :pick
 *     invoke-virtual {vRes, vR}, Resources->getString(I)
 *
 * Null when the method has no such site.
 */
internal fun Method.searchHintSite(): Int? {
    val code = implementation?.instructions?.toList() ?: return null
    for (pick in code.indices) {
        val get = code[pick] as? FiveRegisterInstruction ?: continue
        if (get.opcode != Opcode.INVOKE_VIRTUAL || (get as ReferenceInstruction).reference.toString() != GET_STRING || pick < 7) continue
        val result = pick - 6
        val check = (code[pick - 7] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        val held = (code[result] as? OneRegisterInstruction)?.registerA ?: continue
        val resources = (code[pick - 5] as? ReferenceInstruction)?.reference as? MethodReference
        val plain = code[pick - 3]
        val branch = code[pick - 2]
        val aiHint = code[pick - 1]
        if (code[pick - 7].opcode != Opcode.INVOKE_VIRTUAL || check.returnType != "Z" ||
            check.parameterTypes.map { it.toString() } != listOf(FB_USER_SESSION) || code[result].opcode != Opcode.MOVE_RESULT ||
            resources?.returnType != "Landroid/content/res/Resources;" || code[pick - 4].opcode != Opcode.MOVE_RESULT_OBJECT ||
            (code[pick - 4] as OneRegisterInstruction).registerA != get.registerC ||
            plain.opcode != Opcode.CONST || aiHint.opcode != Opcode.CONST ||
            (plain as OneRegisterInstruction).registerA != get.registerD || (aiHint as OneRegisterInstruction).registerA != get.registerD ||
            branch.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != held || code.branchTarget(pick - 2) != pick) continue
        return result
    }
    return null
}

internal fun Method.isSearchBarBuilder(): Boolean =
    implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == SEARCH_BAR_TAG } == true &&
        searchHintSite() != null

/** The one search bar builder, or none when more than one method looks like it. */
internal fun findAiSearchHint(classes: Iterable<ClassDef>): List<Method> =
    classes.flatMap { it.methods }.filter { it.isSearchBarBuilder() }.let { if (it.size == 1) it else emptyList() }

internal fun MutableMethod.validateAiSearchHint(): Int {
    if (!isSearchBarBuilder()) hintChanged("unexpected search bar ${hookId()}")
    val result = searchHintSite()!!
    // Only the read itself may reach the check's answer, or a path could skip the extension.
    if ((result + 1) in jumpTargets()) hintChanged("a jump past the Meta AI check")
    return result
}

/**
 * Passes the Meta AI check through the extension right after the builder reads it. With the switch on it answers false,
 * the answer accounts without Meta AI in search get, so the bar shows Messenger's plain hint.
 */
internal fun MutableMethod.injectAiSearchHint() {
    val result = validateAiSearchHint()
    val held = (implementation!!.instructions.elementAt(result) as OneRegisterInstruction).registerA
    addInstructions(result + 1, """
        invoke-static/range {v$held .. v$held}, $AI_SEARCH_HINT_HELPER
        move-result v$held
    """.trimIndent())
}
