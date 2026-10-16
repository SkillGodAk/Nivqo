package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** How many chats Messenger lets you pin above the chat list. */
internal const val PINNED_CHATS = "pinned_chats"

/**
 * The config number Messenger reads its pinned chat limit from. One getter loads it, and the Pin row's check before a
 * pin, the recount after one, the chat list's fetch of pinned chats and the thread details' Pin state all compare the
 * pinned count with that getter's answer.
 */
internal const val PINNED_CHAT_LIMIT_CONFIG = 0x8205c10002107bL

internal const val PINNED_CHAT_LIMIT_GATE = "$SETTINGS->pinnedChatLimit(I)I"

private val RETURNS = setOf(Opcode.RETURN, Opcode.RETURN_VOID, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE)

private fun pinLimitChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the pinned chat limit changed ($detail)")

/**
 * Index of the getter's `return`, or null when this isn't the limit's getter. The getter is a static (session)I that
 * loads the limit's config id once, has no branch and no handler, and ends by returning the int its last call answered.
 */
internal fun Method.pinnedChatLimitReturn(): Int? {
    if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != "I" || parameterTypes.map { it.toString() } != listOf(FB_USER_SESSION)) return null
    val body = implementation ?: return null
    val code = body.instructions.toList()
    if (code.count { it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == PINNED_CHAT_LIMIT_CONFIG } != 1) return null
    if (code.any { it is OffsetInstruction } || body.tryBlocks.isNotEmpty() || code.count { it.opcode in RETURNS } != 1) return null
    val last = code.lastIndex
    if (last < 2 || code[last].opcode != Opcode.RETURN || code[last - 1].opcode != Opcode.MOVE_RESULT) return null
    if ((code[last] as OneRegisterInstruction).registerA != (code[last - 1] as OneRegisterInstruction).registerA) return null
    val read = (code[last - 2] as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    return if (read.returnType == "I") last else null
}

/** The limit's getter, when exactly one method loads the limit that way. */
internal fun findPinnedChatLimit(classes: Iterable<ClassDef>): List<Method> =
    classes.flatMap { it.methods }.filter { it.pinnedChatLimitReturn() != null }.let { if (it.size == 1) it else emptyList() }

internal fun MutableMethod.validatePinnedChatLimit(): Int =
    pinnedChatLimitReturn() ?: pinLimitChanged("unexpected method ${hookId()}")

/**
 * The getter hands Messenger's own limit to the extension just before returning it, and returns what comes back. With
 * the switch on that's a higher number, so every check that asks sees the same one. Off, Pause and safe mode give the
 * number back unchanged.
 */
internal fun MutableMethod.injectPinnedChatLimit() {
    val back = validatePinnedChatLimit()
    val register = (implementation!!.instructions.elementAt(back) as OneRegisterInstruction).registerA
    addInstructions(back, """
        invoke-static/range {v$register .. v$register}, $PINNED_CHAT_LIMIT_GATE
        move-result v$register
    """.trimIndent())
}
