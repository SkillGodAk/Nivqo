/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.transitions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * The slides a screen Facebook opens inside its own, like a post's page, comes in and goes out with.
 * Facebook's fragments are androidx's under Redex names (583: FragmentTransaction `LX/0bH;`, its one
 * subclass BackStackRecord `LX/0Aa;`). The in-app push (583 `LX/2WM;->A0R`) takes four animations
 * from a table, a slide in from the side, the page under it fading back, and the two Back plays,
 * and hands them to the transaction's setCustomAnimations, which only keeps them:
 *
 *     iput p1, p0, LX/0bH;->A02:I    # enter
 *     iput p2, p0, LX/0bH;->A03:I    # exit
 *     iput p3, p0, LX/0bH;->A04:I    # pop enter
 *     iput p4, p0, LX/0bH;->A05:I    # pop exit
 *     return-void
 *
 * Each op added after it copies the four (addOp), and the FragmentManager plays them as the screen
 * comes and as Back takes it away. With the switch on, the patch has each argument go through the
 * extension first, which hands back 0, no animation. 39 methods call it on 583, and the one hook
 * covers them all.
 *
 * The setter has no name to find it by. BackStackRecord's dump prints the four after "mEnterAnim=#"
 * and its three siblings, so the patch reads the fields from there and takes the transaction's one
 * (IIII)V method that keeps its arguments in them, in order.
 */

internal const val ENTER_ANIM_LOG = "mEnterAnim=#"
internal const val POP_ENTER_ANIM_LOG = "mPopEnterAnim=#"
internal const val FRAGMENT_ANIMATION = "$SCREEN_TRANSITIONS->fragmentAnimation(I)I"

/** What BackStackRecord's dump prints before each of the four, in setCustomAnimations' order. */
private val ANIMATION_LABELS = listOf(ENTER_ANIM_LOG, " mExitAnim=#", POP_ENTER_ANIM_LOG, " mPopExitAnim=#")

private val Instruction.field: FieldReference?
    get() = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.prints(label: String): Boolean =
    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
        ((this as ReferenceInstruction).reference as? StringReference)?.string == label

private fun FieldReference.smali() = "$definingClass->$name:$type"

/** The four int fields BackStackRecord's [dump] prints: enter, exit, pop enter and pop exit. */
internal fun fragmentAnimationFields(dump: Method): List<FieldReference> {
    val where = "${dump.definingClass}->${dump.name}"
    val code = dump.implementation?.instructions?.toList() ?: throw PatchException("$PATCH: $where has no code")
    val fields = ANIMATION_LABELS.map { label ->
        val at = code.indexOfFirst { it.prints(label) }
        if (at < 0) throw PatchException("$PATCH: $where doesn't print \"$label\"")
        // The label goes to the writer, then the field is read for it.
        code.drop(at + 1).take(3).firstOrNull { it.opcode == Opcode.IGET }?.field
            ?: throw PatchException("$PATCH: $where reads no field for \"$label\"")
    }
    val owners = fields.map { it.definingClass }.toSet()
    if (owners.size != 1) throw PatchException("$PATCH: $where prints the animations from ${owners.size} classes, expected one")
    if (fields.map { it.smali() }.toSet().size != 4 || fields.any { it.type != "I" }) {
        throw PatchException("$PATCH: $where prints ${fields.joinToString { it.smali() }}, expected four int fields")
    }
    return fields
}

/** Whether this is setCustomAnimations: an (IIII)V instance method keeping its arguments in [fields], first to last. */
internal fun Method.keepsAnimations(fields: List<FieldReference>): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != "V") return false
    if (parameterTypes.map(CharSequence::toString) != listOf("I", "I", "I", "I")) return false
    val implementation = implementation ?: return false
    val first = implementation.registerCount - 4
    val kept = implementation.instructions.filter { it.opcode == Opcode.IPUT }
    return kept.size == 4 && kept.withIndex().all { (index, put) ->
        (put as TwoRegisterInstruction).registerA == first + index && put.field?.smali() == fields[index].smali()
    }
}

/**
 * Has each animation the fragment transaction's setCustomAnimations is handed go through the
 * extension before it's kept. [dump] is BackStackRecord's dump, which names the fields.
 */
internal fun BytecodePatchContext.quietFragmentSlides(dump: Method) {
    val fields = fragmentAnimationFields(dump)
    val type = fields.first().definingClass
    val transaction = mutableClassDefByOrNull(type) ?: throw PatchException("$PATCH: no $type for the fragment transaction")
    val setters = transaction.methods.filter { it.keepsAnimations(fields) }
    val setter = setters.singleOrNull()
        ?: throw PatchException("$PATCH: $type has ${setters.size} methods keeping the four animations, expected setCustomAnimations alone")
    setter.addInstructions(0, (1..4).joinToString("\n") { "invoke-static { p$it }, $FRAGMENT_ANIMATION\nmove-result p$it" })
}
