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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
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
        val kept = FixtureDex.classes(bundle, setOf(NOTIFICATION_TYPE, MESSAGES_NOTIFICATION_MANAGER))
        val notificationType = kept[NOTIFICATION_TYPE] ?: throw AssertionError("${bundle.name} has no $NOTIFICATION_TYPE")
        val voip = voipConstant(notificationType) ?: throw AssertionError("${bundle.name}: $NOTIFICATION_TYPE stores no $VOIP")
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (isRingParser(classDef) || claimsVoip(classDef, voip)) found += ImmutableClassDef.of(classDef)
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
