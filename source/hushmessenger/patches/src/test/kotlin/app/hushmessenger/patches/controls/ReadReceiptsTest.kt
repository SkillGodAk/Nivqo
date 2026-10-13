package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val SEEN_HANDLER = "LX/4lQ;"
internal const val SYNTHETIC_CHAT_SEEN = "$SEEN_HANDLER->A00($FB_USER_SESSION${SEEN_HANDLER}Ljava/lang/String;)V"
internal const val SYNTHETIC_SEND_EVENT = "$SEND_MESSAGE_EVENT-><init>(LX/5Wz;LX/B0r;Ljava/lang/String;Ljava/lang/String;Z)V"

/** The seen handler's schedule method cut down: it bails out when Messenger suppresses it, else names the job. */
private val SEEN_BODY = """
    iget-object v0, p1, $SEEN_HANDLER->A09:Ljava/util/concurrent/atomic/AtomicBoolean;
    const-string v1, "mark_read_broadcast"
    const-string v1, "$CHAT_SEEN_TRACE"
    const-string v0, "$CHAT_SEEN_JOB"
    return-void
""".trimIndent()

/** The event's constructor: the super call, the field stores, one return at the end. */
private val SEND_BODY = """
    invoke-direct {v0}, Ljava/lang/Object;-><init>()V
    iput-object v2, v0, $SEND_MESSAGE_EVENT->A01:LX/B0r;
    iput-boolean v5, v0, $SEND_MESSAGE_EVENT->A04:Z
    return-void
""".trimIndent()

private fun seen(
    id: String = SYNTHETIC_CHAT_SEEN,
    body: String = SEEN_BODY,
    flags: Int = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value,
): MutableMethod = fixtureMethod(id, body, registers = 9, flags = flags)

private fun send(id: String = SYNTHETIC_SEND_EVENT, body: String = SEND_BODY): MutableMethod =
    fixtureMethod(id, body, registers = 6, flags = AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value)

/** The handler beside the read manager it doesn't touch, and the send event beside its own factory. */
internal fun readReceiptsFixture(seenMethod: MutableMethod = seen(), sendMethod: MutableMethod = send()): List<MutableClass> = listOf(
    fixtureClass(seenMethod.definingClass, listOf(seenMethod,
        fixtureMethod("${seenMethod.definingClass}->A03($FB_USER_SESSION${"Ljava/lang/String;"})V", "const-string v0, \"mark_read_branch\"\nreturn-void"))),
    fixtureClass(sendMethod.definingClass, listOf(sendMethod,
        fixtureMethod("$SEND_MESSAGE_EVENT->A3a()Ljava/lang/String;", "const/4 v0, 0x0\nreturn-object v0"))),
    fixtureClass("LX/AX0;", listOf(fixtureMethod("LX/AX0;->run()V", "return-void")), "ReadThreadManager\$1"),
)

private fun Method.code() = implementation!!.instructions.toList()

class ReadReceiptsTest {
    @AfterTest fun reset() {
        activeProfile = SYNTHETIC_PROFILE
    }

    private fun found(classes: List<MutableClass>, key: String) = findControls(classes).getValue(key).map { it.hookId() }

    @Test fun theSeenHandlerAndTheSendEventAreFoundAndTheReadManagerIsLeftAlone() {
        val fixture = readReceiptsFixture()
        assertEquals(listOf(SYNTHETIC_CHAT_SEEN), found(fixture, HIDE_READ_RECEIPTS))
        assertEquals(listOf(SYNTHETIC_SEND_EVENT), found(fixture, REPLY_READ))
        validateControls(findControls(fixture), setOf(HIDE_READ_RECEIPTS, REPLY_READ))
        assertTrue(findControls(fixture).values.flatten().none { it.definingClass == "LX/AX0;" })
    }

    @Test fun eachProfileNamesOneSeenHandlerAndTheNamedSendEvent() {
        for (profile in listOf(BASE_PROFILE, PROFILE_346415706)) {
            val seen = profile.hooks.getValue(HIDE_READ_RECEIPTS).single()
            val owner = seen.substringBefore("->")
            assertEquals("$owner->A00($FB_USER_SESSION${owner}Ljava/lang/String;)V", seen)
            assertTrue(profile.hooks.getValue(REPLY_READ).single().startsWith("$SEND_MESSAGE_EVENT-><init>("))
        }
    }

    @Test fun lookalikesAreNotHooked() {
        val seenCases = mapOf(
            "no job name" to seen(body = "const-string v0, \"$CHAT_SEEN_TRACE\"\nreturn-void"),
            "no trace" to seen(body = "const-string v0, \"$CHAT_SEEN_JOB\"\nreturn-void"),
            "not static" to seen(flags = AccessFlags.PUBLIC.value),
            "another handler type" to seen(id = "$SEEN_HANDLER->A00(${FB_USER_SESSION}LX/Other;Ljava/lang/String;)V"),
        )
        for ((case, method) in seenCases) {
            val classes = readReceiptsFixture(seenMethod = method)
            assertTrue(found(classes, HIDE_READ_RECEIPTS).isEmpty(), case)
            assertFailsWith<PatchException>(case) { validateControls(findControls(classes), setOf(HIDE_READ_RECEIPTS)) }
        }
        val sendCases = mapOf(
            "four arguments" to send(id = "$SEND_MESSAGE_EVENT-><init>(LX/5Wz;LX/B0r;Ljava/lang/String;Ljava/lang/String;)V"),
            "another class" to send(id = "LX/SendLike;-><init>(LX/5Wz;LX/B0r;Ljava/lang/String;Ljava/lang/String;Z)V"),
        )
        for ((case, method) in sendCases) {
            val classes = readReceiptsFixture(sendMethod = method)
            assertTrue(found(classes, REPLY_READ).isEmpty(), case)
            assertFailsWith<PatchException>(case) { validateControls(findControls(classes), setOf(REPLY_READ)) }
        }
    }

    @Test fun theSwitchSkipsTheSeenJobBeforeAnyStockWork() {
        val method = seen()
        val before = method.code()
        injectControl(HIDE_READ_RECEIPTS, mapOf(HIDE_READ_RECEIPTS to listOf(method)))
        val code = method.code()
        assertEquals("$SETTINGS->hideChatSeen()Z", (code[0] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, code[2].opcode)
        assertEquals(4, code.branchTarget(2))
        assertEquals(Opcode.RETURN_VOID, code[3].opcode)
        // Off, Pause, safe mode and a just-sent reply fall through to Messenger's own method, unchanged.
        assertEquals(before, code.drop(4))
    }

    @Test fun aSendNotesItselfOnceTheEventIsBuilt() {
        val method = send()
        val before = method.code()
        injectControl(HIDE_READ_RECEIPTS, mapOf(REPLY_READ to listOf(method)))
        val code = method.code()
        assertEquals(before.size + 1, code.size)
        assertEquals(before.dropLast(1), code.take(before.size - 1))
        assertEquals(Opcode.INVOKE_STATIC, code[code.size - 2].opcode)
        assertEquals("$SETTINGS->noteMessageSent()V", (code[code.size - 2] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.RETURN_VOID, code.last().opcode)
    }

    @Test fun anUnusableHookRefusesTheSwitchBeforeAnyEdit() {
        val badSends = mapOf(
            // Two returns: a note before the last one would miss the early exit.
            "two returns" to send(body = "if-eqz v5, :early\nreturn-void\n:early\nreturn-void"),
            "not the constructor" to fixtureMethod("$SEND_MESSAGE_EVENT->A3a()Ljava/lang/String;", "const/4 v0, 0x0\nreturn-object v0"),
        )
        for ((case, bad) in badSends) {
            val good = seen()
            val goodBefore = good.code()
            val badBefore = bad.code()
            assertFailsWith<PatchException>(case) {
                injectControl(HIDE_READ_RECEIPTS, mapOf(HIDE_READ_RECEIPTS to listOf(good), REPLY_READ to listOf(bad)))
            }
            assertEquals(badBefore, bad.code(), case)
            assertEquals(goodBefore, good.code(), case)
        }
        // Three registers are exactly the parameter words, so v0 would be the session.
        val cramped = fixtureMethod(SYNTHETIC_CHAT_SEEN, SEEN_BODY.replace("const-string v1", "const-string v0"),
            registers = 3, flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)
        val good = send()
        val crampedBefore = cramped.code()
        val goodBefore = good.code()
        assertFailsWith<PatchException> {
            injectControl(HIDE_READ_RECEIPTS, mapOf(HIDE_READ_RECEIPTS to listOf(cramped), REPLY_READ to listOf(good)))
        }
        assertEquals(crampedBefore, cramped.code())
        assertEquals(goodBefore, good.code())
    }

    @Test fun everySupportedBuildHasOneSeenHandlerAndOneSendEvent() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString().substringBeforeLast(".apk").substringAfterLast('-')
            activeProfile = controlProfileFor(code)
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val classes = dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }
            val seens = findChatSeen(classes)
            val sends = findReplyRead(classes)
            assertEquals(activeProfile.hooks.getValue(HIDE_READ_RECEIPTS), seens.map { it.hookId() }.toSet(), code)
            assertEquals(activeProfile.hooks.getValue(REPLY_READ), sends.map { it.hookId() }.toSet(), code)
            validateControls(mapOf(HIDE_READ_RECEIPTS to seens, REPLY_READ to sends), setOf(HIDE_READ_RECEIPTS, REPLY_READ))
            val seen = MutableMethod(seens.single())
            val seenBefore = seen.code()
            seen.injectChatSeen()
            assertEquals("$SETTINGS->hideChatSeen()Z", (seen.code()[0] as ReferenceInstruction).reference.toString(), code)
            assertEquals(seenBefore.map { it.opcode }, seen.code().drop(4).map { it.opcode }, code)
            val send = MutableMethod(sends.single())
            val sendBefore = send.code()
            send.injectReplyRead()
            assertEquals(sendBefore.dropLast(1).map { it.opcode }, send.code().dropLast(2).map { it.opcode }, code)
            assertEquals("$SETTINGS->noteMessageSent()V", (send.code().dropLast(1).last() as ReferenceInstruction).reference.toString(), code)
        }
    }
}
