/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Where Facebook rings for an incoming call and posts a chat notification, on the 582 builds
 * (arm64 and armv7 alike), for the Leave calls and chats to Messenger switch the Open Messenger
 * from the top bar patch carries.
 *
 * Call pushes. Facebook hands every push to each of its push handlers, which implement one renamed
 * interface: a claim method, (the push's source, the type String)Z, says whether the handler takes
 * that type, and a handle method, an instance void over (Context, FbUserSession, PushProperty, the
 * parsed push, a logging helper, PushInfraMetaData), takes it. Two handlers on each build claim
 * VOIP pushes alone: their claim is six instructions that read the VOIP constant of the kept enum
 * com.facebook.notifications.constants.push.NotificationType and ask it whether the type is its
 * name. One (it logs "FbRtcPushDataHandler") parses the push into a ring on an executor, the other
 * ("RtcFbPushDataHandler.onNotification") hands it to each of the RTC stack's plugins, the
 * signaling processor and the Page call handler among them. Returning from either handle method
 * first drops the push. Other handlers take VOIP among hundreds of types from a set, and claim by
 * set lookup, so the claim's shape and size leave them out.
 *
 * Rings. Every incoming ring goes through one parse method of the rooms notification parser: from
 * the FCM handler above, from Facebook's own MQTT connection and from its RTC signaling channel.
 * The parser is the one class declaring both Kotlin internal methods getRingType$... and
 * getBinaryPayloadFromPushNotif$..., names Redex keeps. Its parse is its one instance method over
 * (FbUserSession, a renamed enum, the RTC message, Long, byte[]) answering an object: a ring for a
 * RingRequest, a dismissal for a DismissRequest and null for anything else, and every caller takes
 * null as nothing to ring. It's synchronized, by a monitor-enter after its first instruction, so
 * code put in front of that returns before the lock is taken, outside every try block. Answering
 * null first drops the ring.
 *
 * Chats. A new message, a reaction, a message request or a missed call is built as a subclass of
 * the kept com.facebook.messaging.notify.type.MessagingNotification and handed to the kept
 * com.facebook.messaging.notify.client.MessagesNotificationManager. Its static (FbUserSession,
 * MessagesNotificationManager, MessagingNotification)V dispatcher runs the suppression plugins and
 * then hands the notification to Facebook's notifiers through their interface's (FbUserSession,
 * MessagingNotification)V methods, twice. The class's other static method of that shape makes no
 * such call. Returning from the dispatcher first posts nothing. Chat notifications never reach the
 * tray manager Block promotional notifications hooks, which is why that patch can't do this.
 *
 * Everything else here is Redex's naming and changes every build, so the handlers are found by the
 * constant their claim reads and their kept parameter types, the parser by its kept method names,
 * and the dispatcher by its kept class and its shape.
 */

/** The kept enum of push types. */
internal const val NOTIFICATION_TYPE = "Lcom/facebook/notifications/constants/push/NotificationType;"

/** The push type an incoming call arrives as, and the name of its NotificationType constant. */
internal const val VOIP = "VOIP"

/** The prefix of the parser's kept Kotlin internal method that reads a ring's type. */
internal const val RING_TYPE = "getRingType$"

/** The prefix of the parser's kept Kotlin internal method that reads a push's binary payload. */
internal const val RING_PAYLOAD = "getBinaryPayloadFromPushNotif$"

/** The kept class that hands chat notifications to Facebook's notifiers. */
internal const val MESSAGES_NOTIFICATION_MANAGER = "Lcom/facebook/messaging/notify/client/MessagesNotificationManager;"

/** The kept MQTT delegate that builds Facebook's native connection configuration. */
internal const val MQTT_XPLAT_SERVICE_DELEGATE =
    "Lcom/facebook/push/mqtt/service/xplat/MqttXplatServiceDelegate;"

/** The kept connection value whose first long is client capabilities and second is endpoint capabilities. */
internal const val MQTT_CONNECTION_CONFIG = "Lcom/facebook/mqtt/service/ConnectionConfig;"

/** The kept base class of every chat notification. */
internal const val MESSAGING_NOTIFICATION = "Lcom/facebook/messaging/notify/type/MessagingNotification;"

/** The dispatcher's parameter holding the notification. */
internal const val CHAT_NOTIFICATION_PARAMETER = 2

internal const val PUSH_CONTEXT = "Landroid/content/Context;"
internal const val PUSH_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"
internal const val PUSH_PROPERTY = "Lcom/facebook/push/constants/PushProperty;"
internal const val PUSH_INFRA = "Lcom/facebook/pushlite/model/PushInfraMetaData;"

private const val STRING_TYPE = "Ljava/lang/String;"
private const val LONG_OBJECT = "Ljava/lang/Long;"

private val CONNECTION_CONFIG_PREFIX = listOf(
    STRING_TYPE, STRING_TYPE, STRING_TYPE, "I", STRING_TYPE, STRING_TYPE,
    STRING_TYPE, STRING_TYPE, STRING_TYPE, "I", "J", "J",
)

/** The most instructions a claim of one type takes: read the constant, ask it, answer, with room to spare. */
internal const val CLAIM_SIZE = 8

private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun FieldReference.isSameAs(other: FieldReference) =
    definingClass == other.definingClass && name == other.name && type == other.type

/**
 * The static field NotificationType keeps its VOIP constant in: the first field of the enum's own
 * type its static initializer stores into after loading the name, with no other string loaded
 * between. Null when the enum names no VOIP or never stores it that way.
 */
internal fun voipConstant(notificationType: ClassDef): FieldReference? {
    val code = notificationType.methods.singleOrNull { it.name == "<clinit>" }?.code() ?: return null
    val load = code.indexOfFirst { it.string() == VOIP }
    if (load < 0) return null
    for (at in load + 1 until code.size) {
        if (code[at].string() != null) return null
        val field = code[at].field()
        if (code[at].opcode == Opcode.SPUT_OBJECT && field != null &&
            field.definingClass == notificationType.type && field.type == notificationType.type
        ) {
            return field
        }
    }
    return null
}

/**
 * A claim of VOIP pushes alone: an instance method answering a boolean over (an object, String),
 * no longer than [CLAIM_SIZE], that reads [voip] and asks NotificationType a (String)Z question.
 */
internal fun isVoipClaim(method: Method, voip: FieldReference): Boolean {
    // The shape first: this runs over every method of every class, and the body is read last.
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Z" || method.parameterTypes.size != 2) return false
    val parameters = method.parameters()
    if (!parameters[0].startsWith("L") || parameters[1] != STRING_TYPE) return false
    val code = method.code()
    if (code.isEmpty() || code.size > CLAIM_SIZE) return false
    val readsVoip = code.any { it.opcode == Opcode.SGET_OBJECT && it.field()?.isSameAs(voip) == true }
    val asks = code.any { instruction ->
        val call = instruction.method()
        call != null && call.definingClass == NOTIFICATION_TYPE && call.returnType == "Z" &&
            call.parameterTypes.map { it.toString() } == listOf(STRING_TYPE)
    }
    return readsVoip && asks
}

/**
 * A push handler's handle method: an instance void over (Context, FbUserSession, PushProperty, the
 * push, a helper, PushInfraMetaData).
 */
internal fun isPushHandle(method: Method): Boolean {
    val parameters = method.parameters()
    return method.implementation != null && !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        parameters.size == 6 && parameters[0] == PUSH_CONTEXT && parameters[1] == PUSH_SESSION &&
        parameters[2] == PUSH_PROPERTY && parameters[3].startsWith("L") && parameters[4].startsWith("L") &&
        parameters[5] == PUSH_INFRA
}

/** Whether [handler] claims VOIP pushes alone, by one of its methods. */
internal fun claimsVoip(handler: ClassDef, voip: FieldReference): Boolean = handler.methods.any { isVoipClaim(it, voip) }

/** Whether [classDef] is the rooms notification parser: it declares both kept Kotlin internal methods. */
internal fun isRingParser(classDef: ClassDef): Boolean =
    classDef.methods.any { it.name.startsWith(RING_TYPE) } && classDef.methods.any { it.name.startsWith(RING_PAYLOAD) }

/**
 * The parser's parse: an instance method over (FbUserSession, an object, an object, Long, byte[])
 * answering an object.
 */
internal fun isRingParse(method: Method): Boolean {
    val parameters = method.parameters()
    return method.implementation != null && !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.returnType.startsWith("L") && parameters.size == 5 && parameters[0] == PUSH_SESSION &&
        parameters[1].startsWith("L") && parameters[2].startsWith("L") && parameters[3] == LONG_OBJECT &&
        parameters[4] == "[B"
}

/** Whether [instruction] hands a notification to a notifier: an interface call of (FbUserSession, MessagingNotification)V. */
internal fun isNotifierCall(instruction: Instruction): Boolean {
    if (instruction.opcode != Opcode.INVOKE_INTERFACE && instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE) return false
    val call = instruction.method() ?: return false
    return call.returnType == "V" && call.parameterTypes.map { it.toString() } == listOf(PUSH_SESSION, MESSAGING_NOTIFICATION)
}

/**
 * The chat dispatcher: a static void over (FbUserSession, MessagesNotificationManager,
 * MessagingNotification) that hands the notification to a notifier.
 */
internal fun isChatDispatch(method: Method): Boolean =
    method.implementation != null && AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        method.parameters() == listOf(PUSH_SESSION, MESSAGES_NOTIFICATION_MANAGER, MESSAGING_NOTIFICATION) &&
        method.code().any(::isNotifierCall)

/** The MQTT method and constructor argument that carry Facebook's advertised client capabilities. */
internal data class MqttCapabilitiesTarget(
    val method: Method,
    val constructionIndex: Int,
    val capabilitiesRegister: Int,
)

/**
 * Finds the one ConnectionConfig construction in the kept MQTT delegate. Its constructor is a
 * Kotlin data-class constructor: after the receiver and ten one-register parameters, its first
 * long is the named `capabilities` field and its second long is `endpointCapabilities`.
 */
internal fun mqttCapabilitiesTarget(delegate: ClassDef): MqttCapabilitiesTarget? {
    val providers = delegate.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == MQTT_CONNECTION_CONFIG &&
            method.parameters() == listOf("Z") && method.implementation != null
    }
    val provider = providers.singleOrNull() ?: return null
    val constructions = provider.code().mapIndexedNotNull { index, instruction ->
        val call = instruction.method() ?: return@mapIndexedNotNull null
        val range = instruction as? RegisterRangeInstruction ?: return@mapIndexedNotNull null
        if (instruction.opcode != Opcode.INVOKE_DIRECT_RANGE || call.definingClass != MQTT_CONNECTION_CONFIG ||
            call.name != "<init>" || call.returnType != "V" ||
            call.parameterTypes.map(Any::toString).take(CONNECTION_CONFIG_PREFIX.size) != CONNECTION_CONFIG_PREFIX
        ) {
            return@mapIndexedNotNull null
        }
        // Argument 0 is the receiver. The first ten constructor parameters each take one register.
        val capabilities = range.startRegister + 11
        if (capabilities + 1 >= range.startRegister + range.registerCount) return@mapIndexedNotNull null
        MqttCapabilitiesTarget(provider, index, capabilities)
    }
    return constructions.singleOrNull()
}
