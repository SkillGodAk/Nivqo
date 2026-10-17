/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classes of one fixture bundle that Leave calls and chats to Messenger reads: NotificationType,
 * MessagesNotificationManager, every class claiming VOIP pushes alone and every ring parser, copied
 * out of the dex, for a test that runs the patch on a build's own classes.
 */
internal object HandoffFixture {
    fun classes(bundle: File): List<ClassDef> {
        val kept = FixtureDex.classes(bundle, setOf(
            NOTIFICATION_TYPE,
            MESSAGES_NOTIFICATION_MANAGER,
            MQTT_XPLAT_SERVICE_DELEGATE,
            MQTT_CONNECTION_CONFIG,
        ))
        val notificationType = kept[NOTIFICATION_TYPE] ?: throw AssertionError("${bundle.name} has no $NOTIFICATION_TYPE")
        val voip = voipConstant(notificationType) ?: throw AssertionError("${bundle.name}: $NOTIFICATION_TYPE stores no $VOIP")
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val capabilityEnum = listOf(VOIP, "ENDPOINT_IS_FACEBOOK", "RTC_DROP_IN_CAPABLE").all { wanted ->
                    classDef.methods.any { holdsString(it, wanted) }
                }
                if (isRingParser(classDef) || claimsVoip(classDef, voip) || capabilityEnum) {
                    found += ImmutableClassDef.of(classDef)
                }
            }
        }
        return (kept.values + found).distinctBy { it.type }
    }
}

/**
 * The anchors of Leave calls and chats to Messenger on every declared Facebook build: two push
 * handlers claiming VOIP pushes alone, the one that parses a ring (it logs "FbRtcPushDataHandler")
 * and the one that hands the push to the RTC plugins ("RtcFbPushDataHandler.onNotification"); one
 * rooms notification parser with one parse, whose callers all take null as nothing to ring; one
 * chat dispatcher handing the notification to Facebook's notifiers; a local to borrow in each; and
 * every chat kind the extension leaves to Messenger being a MessagingNotification of the build. The
 * patch itself runs on these in MessengerIconFixtureTest, with the rest of the patch. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class MessengerHandoffFixtureTest {
    private val handoffClass = "Lapp/morphe/extension/facebook/chats/MessengerHandoff;"
    private val future = "Lcom/google/common/util/concurrent/AbstractFuture;"
    private val ringHandlerLog = "FbRtcPushDataHandler"
    private val pluginHandlerLog = "RtcFbPushDataHandler.onNotification"

    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun key(method: MethodReference) =
        method.definingClass + "->" + method.name + method.parameterTypes.joinToString("", "(", ")") + method.returnType

    private fun calls(method: Method, target: Method): Boolean =
        method.implementation?.instructions?.any { it.call?.let(::key) == key(target) } == true

    /** The callers of [target] anywhere in [bundle]. */
    private fun callers(bundle: File, target: Method): List<Method> =
        FixtureDex.methodsWhere(bundle, dexFilter = { dex ->
            dex.methodSection.any { it.definingClass == target.definingClass && it.name == target.name }
        }) { calls(it, target) }

    /** The chat kinds the extension leaves to Messenger, read from the dex the bundle carries, as types. */
    private fun extensionChatKinds(): Set<String> {
        val kinds = ExtensionDex.classDef(handoffClass).methods.flatMap { method ->
            method.implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                .orEmpty()
        }.filter { it.startsWith("com.facebook.") }.map { "L" + it.replace('.', '/') + ";" }.toSet()
        assertEquals("chat kinds the extension leaves to Messenger: $kinds", 5, kinds.size)
        return kinds
    }

    @Test
    fun `each declared build has two VOIP handlers, one ring parse and one chat dispatcher, each with a local to borrow`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val kinds = extensionChatKinds()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = HandoffFixture.classes(bundle)
                val voip = voipConstant(classes.single { it.type == NOTIFICATION_TYPE })
                assertNotNull("$name: NotificationType's VOIP constant", voip)

                // Facebook's kept MQTT provider passes stock client capabilities as the first long
                // of ConnectionConfig. Its source is 0x17 or 0x217, both of which claim the VOIP
                // bit at ordinal 1 in the build's capability enum.
                val mqtt = mqttCapabilitiesTarget(classes.single { it.type == MQTT_XPLAT_SERVICE_DELEGATE })
                assertNotNull("$name: MQTT client capabilities", mqtt)
                val mqttCode = mqtt!!.method.implementation!!.instructions.toList()
                val handoffMove = mqttCode.take(mqtt.constructionIndex).mapIndexedNotNull { index, instruction ->
                    val move = instruction as? TwoRegisterInstruction ?: return@mapIndexedNotNull null
                    if (move.registerA == mqtt.capabilitiesRegister && instruction.opcode in setOf(
                            Opcode.MOVE_WIDE, Opcode.MOVE_WIDE_FROM16, Opcode.MOVE_WIDE_16,
                        )
                    ) index to move.registerB else null
                }.lastOrNull()
                assertNotNull("$name: client capabilities aren't moved into ConnectionConfig", handoffMove)
                val widened = mqttCode.take(handoffMove!!.first).mapIndexedNotNull { index, instruction ->
                    val conversion = instruction as? TwoRegisterInstruction ?: return@mapIndexedNotNull null
                    if (instruction.opcode == Opcode.INT_TO_LONG && conversion.registerA == handoffMove.second) {
                        index to conversion.registerB
                    } else null
                }.lastOrNull()
                assertNotNull("$name: client capabilities aren't widened from their bit mask", widened)
                val stockMasks = mqttCode.take(widened!!.first).mapNotNull { instruction ->
                    val register = (instruction as? OneRegisterInstruction)?.registerA
                    val literal = instruction as? NarrowLiteralInstruction
                    if (register == widened.second) literal?.narrowLiteral else null
                }.toSet()
                assertTrue("$name: stock MQTT client masks $stockMasks don't claim VOIP",
                    0x17 in stockMasks && 0x217 in stockMasks &&
                        (0x17 and MessengerHandoffCapability.VOIP) != 0 &&
                        (0x217 and MessengerHandoffCapability.VOIP) != 0)
                val capabilityEnums = classes.filter { classDef ->
                    listOf(VOIP, "ENDPOINT_IS_FACEBOOK", "RTC_DROP_IN_CAPABLE").all { wanted ->
                        classDef.methods.any { holdsString(it, wanted) }
                    }
                }
                assertEquals("$name: MQTT capability enums", 1, capabilityEnums.size)
                val enumCode = capabilityEnums.single().methods.single { it.name == "<clinit>" }
                    .implementation!!.instructions.toList()
                val voipName = enumCode.indexOfFirst { instruction ->
                    (instruction as? ReferenceInstruction)?.reference.let { it as? StringReference }?.string == VOIP
                }
                val voipOrdinal = enumCode.drop(voipName + 1).takeWhile { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == null
                }.filterIsInstance<NarrowLiteralInstruction>().firstOrNull()?.narrowLiteral
                assertEquals("$name: VOIP capability ordinal", MessengerHandoffCapability.VOIP_ORDINAL, voipOrdinal)

                // Calls: two handlers claim VOIP alone, each with one handle method, one parsing a
                // ring and one handing the push to the RTC plugins, and both are push handlers of
                // one interface declaring the claim and the handle.
                val handlers = classes.filter { claimsVoip(it, voip!!) }
                assertEquals("$name: handlers claiming $VOIP alone", 2, handlers.size)
                val handles = handlers.map { handler ->
                    val found = handler.methods.filter(::isPushHandle)
                    assertEquals("$name: handle methods of ${handler.type}", 1, found.size)
                    found.single()
                }
                for (handle in handles) assertTrue("$name: ${key(handle)} has no local", handle.localRegisterCount() >= 1)
                assertEquals("$name: what the two handlers log", setOf(setOf(ringHandlerLog), setOf(pluginHandlerLog)),
                    handles.map { handle -> listOf(ringHandlerLog, pluginHandlerLog).filter { holdsString(handle, it) }.toSet() }.toSet())
                val interfaces = FixtureDex.classes(bundle, handlers.flatMap { it.interfaces }.toSet())
                for (handler in handlers) {
                    val claim = handler.methods.single { isVoipClaim(it, voip!!) }
                    val handle = handles.single { it.definingClass == handler.type }
                    fun Method.signature() = name + parameterTypes.joinToString("", "(", ")") + returnType
                    assertTrue("$name: ${handler.type} implements no interface declaring its claim and its handle",
                        handler.interfaces.mapNotNull { interfaces[it] }.any { declared ->
                            val declares = declared.methods.map { it.signature() }
                            claim.signature() in declares && handle.signature() in declares
                        })
                }

                // Rings: one parser, one parse, taking its lock after its first instruction and
                // outside every try block, over the RTC message built from a body and a header, and
                // answering an interface.
                val parsers = classes.filter(::isRingParser)
                assertEquals("$name: classes declaring $RING_TYPE... and $RING_PAYLOAD...", 1, parsers.size)
                val parses = parsers.single().methods.filter(::isRingParse)
                assertEquals("$name: ring parses", 1, parses.size)
                val parse = parses.single()
                assertTrue("$name: the parse has no local", parse.localRegisterCount() >= 1)
                val parseCode = parse.implementation!!.instructions.toList()
                assertEquals("$name: the parse doesn't take its lock second", Opcode.MONITOR_ENTER, parseCode[1].opcode)
                assertTrue("$name: a try block covers the parse's first instruction",
                    parse.implementation!!.tryBlocks.all { it.startCodeAddress > 0 })
                val parseTypes = FixtureDex.classes(bundle, setOf(parse.parameterTypes[2].toString(), parse.returnType))
                assertTrue("$name: the parse's message isn't built from an RtcMessageBody and an RtcMessageHeader",
                    parseTypes.getValue(parse.parameterTypes[2].toString()).methods.any { init ->
                        init.name == "<init>" && init.parameterTypes.map { it.toString() } == listOf(
                            "Lcom/facebook/fbwebrtc/multiway/RtcMessageBody;", "Lcom/facebook/fbwebrtc/multiway/RtcMessageHeader;")
                    })
                assertTrue("$name: the parse doesn't answer an interface",
                    AccessFlags.INTERFACE.isSet(parseTypes.getValue(parse.returnType).accessFlags))

                // Each caller takes null: Facebook's MQTT ring path branches on it, and the three
                // runnables, from FCM and from the signaling channel, put it in the future the
                // handler waits on, the same future they put null in for a push that isn't a ring.
                val parseCallers = callers(bundle, parse)
                assertEquals("$name: callers of the parse ${parseCallers.map(::key)}", 4, parseCallers.size)
                for (caller in parseCallers) {
                    val code = caller.implementation!!.instructions.toList()
                    val at = code.indexOfFirst { it.call?.let(::key) == key(parse) }
                    val next = code.getOrNull(at + 1)
                    assertEquals("$name: ${key(caller)} drops the parse's answer", Opcode.MOVE_RESULT_OBJECT, next?.opcode)
                    val result = (next as OneRegisterInstruction).registerA
                    val branchesOnNull = code.drop(at + 2).any {
                        it.opcode == Opcode.IF_EQZ && (it as OneRegisterInstruction).registerA == result
                    }
                    val handsOn = caller.name == "run" && code.any { it.call?.definingClass == future && it.call?.name == "set" }
                    assertTrue("$name: ${key(caller)} doesn't take a null parse", branchesOnNull || handsOn)
                }

                // Chats: one dispatcher of MessagesNotificationManager hands the notification to
                // the notifiers, twice, with a local to borrow.
                val manager = classes.single { it.type == MESSAGES_NOTIFICATION_MANAGER }
                val dispatches = manager.methods.filter(::isChatDispatch)
                assertEquals("$name: chat dispatchers", 1, dispatches.size)
                val dispatch = dispatches.single()
                assertEquals("$name: notifier calls in the dispatcher", 2,
                    dispatch.implementation!!.instructions.count(::isNotifierCall))
                assertTrue("$name: the dispatcher has no local", dispatch.localRegisterCount() >= 1)

                // Every chat kind the extension leaves to Messenger is a MessagingNotification here.
                val kindClasses = FixtureDex.classes(bundle, kinds)
                assertEquals("$name: chat kinds this build lacks", emptySet<String>(), kinds - kindClasses.keys)
                for (kind in kindClasses.values) {
                    assertEquals("$name: ${kind.type} isn't a MessagingNotification", MESSAGING_NOTIFICATION, kind.superclass)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}

/** Values shared with the runtime hook, repeated here so a fixture change fails before packaging. */
private object MessengerHandoffCapability {
    const val VOIP_ORDINAL = 1
    const val VOIP = 1 shl VOIP_ORDINAL
}
