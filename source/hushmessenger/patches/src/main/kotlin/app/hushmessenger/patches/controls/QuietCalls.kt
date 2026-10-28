package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val QUIET_CALLS = "quiet_calls"

/**
 * The incoming call's alert decision, Messenger's own Runnable for a call that starts ringing. It reads four flags:
 * ringtone off, call notifications blocked, and two auto-connect cases. Any of them skips both the ringtone ("Maybe
 * start ringing") and the vibration ("Maybe start vibrate") and logs ring_alert_suppressed with the four flags. The
 * call itself, its notification and the missed-call row all come from elsewhere.
 */
internal const val QUIET_CALL_RING = "quiet_call_ring"

/**
 * The call service's notification poster. It takes the incoming call's notification as built, posts it and moves the
 * service to the foreground with it, so the notification passes through here before Android sees it.
 */
internal const val QUIET_CALL_NOTIFICATION = "quiet_call_notification"

internal const val RING_MARK = "Maybe start ringing"
internal const val RINGTONE_DISABLED = "ringtone_disabled"
internal const val NOTIFICATIONS_BLOCKED = "notifications_blocked"
internal const val RTC_NOTIFICATION_SERVICE = "Lcom/facebook/rtc/notification/RtcNotificationForegroundService;"
internal const val QUIET_CALLS_HELPER = "Lapp/hushmessenger/extension/QuietCalls;"
internal const val QUIET_CALL_NOTIFICATION_CALL =
    "$QUIET_CALLS_HELPER->incomingNotification(Landroid/app/Notification;Landroid/content/Context;)Landroid/app/Notification;"

private const val BOOLEAN_TEXT = "Ljava/lang/String;->valueOf(Z)Ljava/lang/String;"
private const val NOTIFICATION = "Landroid/app/Notification;"
private const val CONTEXT = "Landroid/content/Context;"

private fun quietCallsChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the incoming call alert changed ($detail)")

private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/** The one register a String.valueOf(boolean) call reads, in either invoke form, or null for anything else. */
private fun Instruction.booleanText(): Int? {
    if ((this as? ReferenceInstruction)?.reference?.toString() != BOOLEAN_TEXT) return null
    return when (opcode) {
        Opcode.INVOKE_STATIC -> (this as FiveRegisterInstruction).takeIf { it.registerCount == 1 }?.registerC
        Opcode.INVOKE_STATIC_RANGE -> (this as RegisterRangeInstruction).takeIf { it.registerCount == 1 }?.startRegister
        else -> null
    }
}

/** Where the guard goes, the ringtone-off flag it sets, and the skip block that flag's branch lands on. */
internal class QuietRing(val index: Int, val flag: Int, val skip: Int)

/**
 * The decision's ringtone-off branch: the one `if-nez` on the flag the skip block logs as ringtone_disabled, placed
 * before "Maybe start ringing" and landing between it and that log, past the vibration. The guard goes right after
 * the branch, where only the branch's fall-through arrives, so it runs on every way in. Null when the method isn't the
 * decision or the shape changed.
 */
internal fun Method.quietRing(): QuietRing? {
    if (AccessFlags.STATIC.isSet(accessFlags) || name != "run" || parameterTypes.isNotEmpty() || returnType != "V") return null
    val code = implementation?.instructions?.toList() ?: return null
    fun only(text: String) = code.indices.filter { code[it].string() == text }.singleOrNull()
    val ring = only(RING_MARK) ?: return null
    val logged = only(RINGTONE_DISABLED) ?: return null
    if (only(NOTIFICATIONS_BLOCKED) == null) return null
    val flag = code.getOrNull(logged + 1)?.booleanText() ?: return null
    val branch = (0 until ring).filter { i ->
        code[i].opcode == Opcode.IF_NEZ && (code[i] as OneRegisterInstruction).registerA == flag && code.branchTarget(i) in ring + 1..logged
    }.singleOrNull() ?: return null
    if (branch + 1 in jumpTargets()) return null
    return QuietRing(branch + 1, flag, code.branchTarget(branch))
}

/** The app's only method that logs both "Maybe start ringing" and ringtone_disabled, when it has that branch. */
internal fun findQuietCallRing(classes: Iterable<ClassDef>): List<Method> {
    val deciders = classes.flatMap { cls ->
        cls.methods.filter { method -> method.implementation?.instructions?.any { it.string() == RING_MARK } == true }
    }
    val decider = deciders.singleOrNull() ?: return emptyList()
    return if (decider.quietRing() != null) listOf(decider) else emptyList()
}

internal fun MutableMethod.validateQuietCallRing(): QuietRing = quietRing() ?: quietCallsChanged("no ringtone branch in ${hookId()}")

/**
 * While the switch is on the decision takes Messenger's own ringtone-off path, the one Do Not Disturb and a silenced
 * call use: no ringtone and no vibration, and ring_alert_suppressed logs ringtone_disabled as true. On that path the
 * flag was false, so with the switch off the guard writes false back and Messenger's code runs unchanged. Off, Pause
 * and safe mode leave it false.
 */
internal fun MutableMethod.injectQuietCallRing() {
    val ring = validateQuietCallRing()
    addInstructionsWithLabels(ring.index, """
        invoke-static {}, $SETTINGS->quietIncomingCalls()Z
        move-result v${ring.flag}
        if-nez v${ring.flag}, :quiet_call
    """.trimIndent(), ExternalLabel("quiet_call", getInstruction(ring.skip)))
}

private fun Method.callsStartForeground() = implementation?.instructions?.any { instruction ->
    val target = (instruction as? ReferenceInstruction)?.reference as? MethodReference
    instruction.opcode == Opcode.INVOKE_VIRTUAL && target?.name == "startForeground" &&
        target.parameterTypes.map { it.toString() }.let { it.size >= 2 && it[0] == "I" && it[1] == NOTIFICATION }
} == true

/** An instance (Notification, Context, ...)V method of the call service that starts the foreground with it. */
internal fun Method.isCallNotificationPoster(): Boolean = definingClass == RTC_NOTIFICATION_SERVICE &&
    !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
    parameterTypes.map { it.toString() }.let { it.size >= 2 && it[0] == NOTIFICATION && it[1] == CONTEXT } && callsStartForeground()

internal fun findQuietCallNotification(classes: Iterable<ClassDef>): List<Method> =
    classes.filter { it.type == RTC_NOTIFICATION_SERVICE }.flatMap { it.methods }.filter { it.isCallNotificationPoster() }
        .singleOrNull()?.let(::listOf).orEmpty()

internal fun MutableMethod.validateQuietCallNotification() {
    if (!isCallNotificationPoster()) quietCallsChanged("${hookId()} isn't the call notification poster")
    if (0 in jumpTargets()) quietCallsChanged("${hookId()} branches back to its start")
}

/**
 * The poster hands its notification to the extension first. With the switch on, an incoming call's notification comes
 * back as a silent one in a group of its own, so Android neither shows it on top of the screen nor opens the call
 * screen over the lock screen. It stays in the notification shade with Answer and Decline. Any other notification,
 * and every notification with the switch off, comes back as it was.
 */
internal fun MutableMethod.injectQuietCallNotification() {
    validateQuietCallNotification()
    addInstructions(0, """
        invoke-static/range {p1 .. p2}, $QUIET_CALL_NOTIFICATION_CALL
        move-result-object p1
    """.trimIndent())
}
