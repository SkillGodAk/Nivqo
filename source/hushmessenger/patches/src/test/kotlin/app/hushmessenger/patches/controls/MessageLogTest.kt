package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val BODY_FIELD = "$MESSAGE->A0n:$SECRET_STRING"
private const val SECRET_FIELD = "$SECRET_STRING->A00:Ljava/lang/String;"
private const val THREAD_KEY_FIELD = "$MESSAGE->A0e:$THREAD_KEY_TYPE"
private const val UNSENT_OWNER = "LX/8MY;"
private const val UNSENT_CHECK = "$UNSENT_OWNER->A03($MESSAGE)Z"
private val STATIC_PUBLIC = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value

/** Messenger's unsent check: the message's unsent time, compared with 0. */
private val UNSENT_BODY = """
    const/4 v0, 0x0
    iget-wide v1, p0, $MESSAGE->A09:J
    const-wide/16 v3, 0x0
    cmp-long v1, v1, v3
    if-eqz v1, :sent
    const/4 v0, 0x1
    :sent
    return v0
""".trimIndent()

private fun field(owner: String, name: String, type: String, flags: Int = AccessFlags.PUBLIC.value) =
    ImmutableField(owner, name, type, flags, null, null, null)

/** The three stock classes the message log reads from, cut down to the shape the resolver checks. */
internal fun messageLogFixture(
    ctorRegisters: Int = 23,
    // Stock shape: the field is read only once "text" is in the set fields, else a shared static empty SecretString.
    bodyGetter: String = """
        iget-object v1, v2, $MESSAGE->A29:Ljava/util/Set;
        const-string v0, "text"
        invoke-interface {v1, v0}, Ljava/util/Set;->contains(Ljava/lang/Object;)Z
        move-result v0
        if-eqz v0, :fallback
        iget-object v0, v2, $BODY_FIELD
        return-object v0
        :fallback
        sget-object v0, $MESSAGE->A2f:$SECRET_STRING
        return-object v0
    """.trimIndent(),
    secretCtor: String = """
        iput-object v3, v2, $SECRET_FIELD
        const/4 v0, 0x0
        iput-object v0, v2, $SECRET_STRING->A01:Ljava/lang/String;
        return-void
    """.trimIndent(),
    ctorBody: String = """
        iget-object v0, p2, $THREAD_KEY_FIELD
        return-void
    """.trimIndent(),
    bodyFlags: Int = AccessFlags.PUBLIC.value,
    secretFlags: Int = AccessFlags.PUBLIC.value,
    retraction: String = UNSEND_RETRACTION,
    unsentBody: String = UNSENT_BODY,
    unsentFlags: Int = STATIC_PUBLIC,
    unsentOwnerFlags: Int = AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value,
): List<MutableClass> {
    val notificationCtor = newMessageNotificationCtor("LX/5qJ;", "LX/5Yc;")
    val secret = fixtureClass(SECRET_STRING, listOf(
        fixtureMethod("$SECRET_STRING-><init>(Ljava/lang/String;)V", secretCtor, 4),
    ), extraFields = listOf(field(SECRET_STRING, "A00", "Ljava/lang/String;", secretFlags), field(SECRET_STRING, "A01", "Ljava/lang/String;")))
    val message = fixtureClass(MESSAGE, listOf(
        fixtureMethod("$MESSAGE->A0G()$SECRET_STRING", bodyGetter, 3),
        // A decoy that also returns a SecretString but names a different field, so "text" has to disambiguate.
        fixtureMethod("$MESSAGE->A0F()$SECRET_STRING", "const-string v0, \"snippet\"\niget-object v0, v1, $MESSAGE->A0m:$SECRET_STRING\nreturn-object v0", 2),
    ), extraFields = listOf(field(MESSAGE, "A0n", SECRET_STRING, bodyFlags), field(MESSAGE, "A0m", SECRET_STRING), field(MESSAGE, "A0e", THREAD_KEY_TYPE), field(MESSAGE, "A09", "J")))
    val notification = fixtureClass(NEW_MESSAGE_NOTIFICATION, listOf(
        fixtureMethod(notificationCtor, ctorBody, ctorRegisters),
        // The Parcel constructor the resolver must skip.
        fixtureMethod("$NEW_MESSAGE_NOTIFICATION-><init>(Landroid/os/Parcel;)V", "return-void", 2),
    ))
    // The manager's recall step: a decoy (Message)Z that reads something else, then the unsent check, then the log.
    val manager = fixtureClass(MESSAGES_NOTIFICATION_MANAGER, listOf(fixtureMethod(
        "$MESSAGES_NOTIFICATION_MANAGER->A0D($FB_USER_SESSION$NEW_MESSAGE_NOTIFICATION)V", """
            iget-object v0, p2, $NEW_MESSAGE_NOTIFICATION->A0K:$MESSAGE
            invoke-static {v0}, LX/2Ji;->A0S($MESSAGE)Z
            invoke-static {v0}, $UNSENT_CHECK
            move-result v1
            const-string v2, "$retraction"
            return-void
        """.trimIndent(), 6)))
    val unsent = fixtureClass(UNSENT_OWNER, listOf(fixtureMethod(UNSENT_CHECK, unsentBody, 6, unsentFlags)), flags = unsentOwnerFlags)
    val decoy = fixtureClass("LX/2Ji;", listOf(fixtureMethod("LX/2Ji;->A0S($MESSAGE)Z",
        "iget-object v0, p0, $MESSAGE->A0e:$THREAD_KEY_TYPE\nif-eqz v0, :none\nconst/4 v0, 0x1\n:none\nreturn v0", 2, STATIC_PUBLIC)))
    return listOf(secret, message, notification, manager, unsent, decoy)
}

private fun MutableMethod.code() = implementation!!.instructions.toList()
private fun reference(at: Int, code: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>) =
    (code[at] as ReferenceInstruction).reference.toString()

class MessageLogTest {
    @AfterTest fun reset() {
        activeProfile = SYNTHETIC_PROFILE
        messageLogContract = null
    }

    private fun ctorOf(classes: List<MutableClass>) = classes.single { it.type == NEW_MESSAGE_NOTIFICATION }
        .methods.single { it.name == "<init>" && it.parameterTypes.firstOrNull() == MESSENGER_ACCOUNT_TYPE }

    @Test fun theConstructorIsFoundAndItsFieldsResolvePerBuild() {
        val classes = messageLogFixture()
        val found = findControls(classes).getValue(MESSAGE_LOG).map { it.hookId() }
        assertEquals(activeProfile.hooks.getValue(MESSAGE_LOG).toList(), found)
        validateControls(findControls(classes), setOf(MESSAGE_LOG))
        val contract = messageLogContract!!
        assertEquals(BODY_FIELD, contract.bodyField)
        assertEquals(SECRET_FIELD, contract.secretField)
        assertEquals(THREAD_KEY_FIELD, contract.threadKeyField)
        assertEquals(UNSENT_CHECK, contract.unsentCheck)
    }

    @Test fun theHookReadsTheMessageAndThreadAndKeepsTheOriginalBody() {
        val classes = messageLogFixture()
        findControls(classes)
        val ctor = ctorOf(classes)
        val before = ctor.code()
        injectControl(MESSAGE_LOG, mapOf(MESSAGE_LOG to listOf(ctor)))
        val code = ctor.code()
        assertEquals("$SETTINGS->logReceivedMessages()Z", reference(0, code))
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(Opcode.IF_EQZ, code[2].opcode)
        val stock = code.size - before.size
        assertEquals(stock, code.branchTarget(2))
        assertEquals(stock, code.branchTarget(4))
        assertEquals(0, (code[3] as OneRegisterInstruction).registerA)
        // An unsent message is only Messenger's stand-in text, so it goes straight to the stock constructor.
        assertEquals(UNSENT_CHECK, reference(5, code))
        assertEquals(1, (code[6] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_NEZ, code[7].opcode)
        assertEquals(stock, code.branchTarget(7))
        assertEquals(BODY_FIELD, reference(8, code))
        assertEquals(stock, code.branchTarget(9))
        assertEquals(SECRET_FIELD, reference(10, code))
        assertEquals(THREAD_KEY_FIELD, reference(11, code))
        assertEquals(Opcode.IF_EQZ, code[12].opcode)
        // Null thread skips toString and passes a null string, so the register stays String-typed on both paths.
        assertEquals(16, code.branchTarget(12))
        assertEquals("$THREAD_KEY_TYPE->toString()Ljava/lang/String;", reference(13, code))
        assertEquals(Opcode.GOTO, code[15].opcode)
        assertEquals(17, code.branchTarget(15))
        assertEquals(Opcode.CONST_4, code[16].opcode)
        assertEquals("$SETTINGS->recordReceivedMessage(Ljava/lang/String;Ljava/lang/String;)V", reference(17, code))
        assertEquals(before, code.drop(stock))
    }

    @Test fun autoReplyReadsTheSameMessageBehindItsOwnGate() {
        val classes = messageLogFixture()
        val found = findControls(classes)
        assertEquals(found.getValue(MESSAGE_LOG).map { it.hookId() }, found.getValue(AUTO_REPLY).map { it.hookId() })
        assertEquals(activeProfile.hooks.getValue(AUTO_REPLY).toList(), found.getValue(AUTO_REPLY).map { it.hookId() })
        validateControls(found, setOf(AUTO_REPLY))
        val ctor = ctorOf(classes)
        val before = ctor.code()
        injectControl(MESSAGE_LOG, mapOf(MESSAGE_LOG to listOf(ctor)))
        val logged = ctor.code()
        injectControl(AUTO_REPLY, mapOf(AUTO_REPLY to listOf(ctor)))
        val code = ctor.code()
        val block = code.size - logged.size
        assertEquals(logged.size - before.size, block)
        assertEquals("$SETTINGS->autoReplyWanted()Z", reference(0, code))
        assertEquals(UNSENT_CHECK, reference(5, code))
        assertEquals(BODY_FIELD, reference(8, code))
        assertEquals("$SETTINGS->autoReplyTo(Ljava/lang/String;Ljava/lang/String;)V", reference(17, code))
        // Each skip in the auto reply block lands on the message log block, which still checks its own gate. An unsend
        // never gets an auto reply either.
        for (skip in listOf(2, 4, 7, 9)) assertEquals(block, code.branchTarget(skip))
        assertEquals("$SETTINGS->logReceivedMessages()Z", reference(block, code))
        assertEquals(before, code.drop(2 * block))
    }

    @Test fun aMissingTextGetterRefusesTheSwitchBeforeAnyEdit() {
        val classes = messageLogFixture(bodyGetter = "const-string v0, \"snippet\"\niget-object v0, v1, $BODY_FIELD\nreturn-object v0")
        findControls(classes)
        assertEquals(null, messageLogContract)
        val ctor = ctorOf(classes)
        val before = ctor.code()
        assertFailsWith<PatchException> { injectControl(MESSAGE_LOG, mapOf(MESSAGE_LOG to listOf(ctor))) }
        assertEquals(before, ctor.code())
    }

    /**
     * The hook reads both fields from the notification constructor, in another package, so a private or package-private
     * one would fail Android's verifier on every notification.
     */
    @Test fun aTextFieldTheNotificationCantReadRefusesTheSwitchBeforeAnyEdit() {
        for (classes in listOf(
            messageLogFixture(bodyFlags = AccessFlags.PRIVATE.value),
            messageLogFixture(secretFlags = AccessFlags.PRIVATE.value),
            messageLogFixture(bodyFlags = 0),
            messageLogFixture(secretFlags = 0),
        )) {
            findControls(classes)
            assertEquals(null, messageLogContract)
            val ctor = ctorOf(classes)
            val before = ctor.code()
            assertFailsWith<PatchException> { injectControl(MESSAGE_LOG, mapOf(MESSAGE_LOG to listOf(ctor))) }
            assertEquals(before, ctor.code())
        }
    }

    @Test fun anUnsentCheckThatDoesNotMatchRefusesTheSwitchBeforeAnyEdit() {
        val cases = mapOf(
            "no retraction log" to messageLogFixture(retraction = "messenger_android_notification_retraction"),
            "reads an object field" to messageLogFixture(unsentBody = UNSENT_BODY.replace("iget-wide v1, p0, $MESSAGE->A09:J", "iget-object v1, p0, $MESSAGE->A0e:$THREAD_KEY_TYPE")),
            "reads two fields" to messageLogFixture(unsentBody = UNSENT_BODY.replace("const-wide/16 v3, 0x0", "iget-wide v3, p0, $MESSAGE->A09:J")),
            "no comparison" to messageLogFixture(unsentBody = "iget-wide v1, p0, $MESSAGE->A09:J\nconst/4 v0, 0x1\nreturn v0"),
            "not static" to messageLogFixture(unsentFlags = AccessFlags.PUBLIC.value),
            "not public" to messageLogFixture(unsentFlags = AccessFlags.STATIC.value),
            // The notification is in another package, so a package-private class would fail the verifier.
            "class not public" to messageLogFixture(unsentOwnerFlags = AccessFlags.ABSTRACT.value),
        )
        for ((case, classes) in cases) {
            findControls(classes)
            assertEquals(null, messageLogContract, case)
            val ctor = ctorOf(classes)
            val before = ctor.code()
            assertFailsWith<PatchException>(case) { injectControl(MESSAGE_LOG, mapOf(MESSAGE_LOG to listOf(ctor))) }
            assertEquals(before, ctor.code(), case)
        }
    }

    @Test fun everySupportedBuildResolvesOneUnsentCheckAndTakesTheHook() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString().substringBeforeLast(".apk").substringAfterLast('-')
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val classes = dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }
            val checks = findUnsentChecks(classes)
            assertEquals(1, checks.size, code)
            val contract = resolveMessageLogContract(classes)
            assertEquals(checks.single(), contract?.unsentCheck, code)
            messageLogContract = contract
            val ctor = MutableMethod(findMessageLogHook(classes).single())
            val before = ctor.code()
            ctor.injectMessageLog()
            val after = ctor.code()
            assertEquals(contract!!.unsentCheck, reference(5, after), code)
            assertEquals(before.map { it.opcode }, after.drop(after.size - before.size).map { it.opcode }, code)
        }
    }

    @Test fun aConstructorWithoutARoomForTheHookIsRefused() {
        val classes = messageLogFixture(ctorRegisters = 22)
        findControls(classes)
        assertTrue(messageLogContract != null)
        val ctor = ctorOf(classes)
        val before = ctor.code()
        assertFailsWith<PatchException> { injectControl(MESSAGE_LOG, mapOf(MESSAGE_LOG to listOf(ctor))) }
        assertEquals(before, ctor.code())
    }
}
