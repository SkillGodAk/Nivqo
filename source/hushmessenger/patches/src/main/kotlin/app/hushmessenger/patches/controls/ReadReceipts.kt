package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val HIDE_READ_RECEIPTS = "hide_read_receipts"
internal const val REPLY_READ = "reply_read"

/**
 * The open chat's seen handler names the job it schedules to mark the chat read. That job ends in the native call
 * that marks the chat read on this phone and sends Seen in one step, so skipping it is the only way to do neither.
 */
internal const val CHAT_SEEN_JOB = "MARK_THREAD_AS_SEEN_JOB"

/** The trace the same handler writes as it schedules the job. Together with the job name it picks one method. */
internal const val CHAT_SEEN_TRACE = "broadcast_message_seen_internal"

/** The composer's send event keeps this name on every 582 build. Sending from a chat's composer builds one. */
internal const val SEND_MESSAGE_EVENT = "Lcom/facebook/xapp/messaging/composer/send/event/SendMessageEvent;"

private fun Method.stringLiterals() =
    implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }?.toSet()

/**
 * The seen handler's static schedule method, (session, handler, message id). Chat-list Mark as read and Mark as unread,
 * the notification's Mark as read and catch up all go through Messenger's read manager instead, which stays stock.
 */
internal fun Method.isChatSeenBroadcast() =
    AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
        parameterTypes.map { it.toString() } == listOf(FB_USER_SESSION, definingClass, "Ljava/lang/String;") &&
        stringLiterals().orEmpty().containsAll(setOf(CHAT_SEEN_JOB, CHAT_SEEN_TRACE))

internal fun Method.isSendMessageEvent() =
    definingClass == SEND_MESSAGE_EVENT && name == "<init>" && returnType == "V" && parameterTypes.size == 5

internal fun findChatSeen(classes: Iterable<ClassDef>): List<Method> =
    classes.flatMap { it.methods }.filter { it.isChatSeenBroadcast() }

internal fun findReplyRead(classes: Iterable<ClassDef>): List<Method> =
    classes.filter { it.type == SEND_MESSAGE_EVENT }.flatMap { it.methods }.filter { it.isSendMessageEvent() }

internal fun MutableMethod.validateChatSeen() {
    if (!isChatSeenBroadcast()) {
        throw PatchException("Messenger controls: ${hookId()} isn't the open chat's seen handler")
    }
    validateSwitch()
}

/**
 * While the switch is on, an open chat neither goes read on this phone nor sends Seen, unless you just sent something in
 * it. Off, Pause and safe mode run Messenger's own code.
 */
internal fun MutableMethod.injectChatSeen() {
    validateChatSeen()
    injectSwitch("hideChatSeen", "0x0")
}

/** The constructor's only return, at its end; returns the index to insert before. */
internal fun MutableMethod.validateReplyRead(): Int {
    val code = implementation?.instructions?.toList().orEmpty()
    if (!isSendMessageEvent() || code.lastOrNull()?.opcode != Opcode.RETURN_VOID || code.count { it.opcode == Opcode.RETURN_VOID } != 1) {
        throw PatchException("Messenger controls: ${hookId()} isn't the composer's send event constructor")
    }
    return code.lastIndex
}

/** The event's fields are already set by then, and the call takes nothing, so no register changes. */
internal fun MutableMethod.injectReplyRead() {
    addInstructions(validateReplyRead(), "invoke-static {}, $SETTINGS->noteMessageSent()V")
}
