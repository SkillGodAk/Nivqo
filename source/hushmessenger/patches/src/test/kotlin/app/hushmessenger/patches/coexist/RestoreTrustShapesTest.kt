package app.hushmessenger.patches.coexist

import app.hushmessenger.patches.controls.controlProfiles
import app.hushmessenger.patches.controls.fixtureMethod
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class RestoreTrustShapesTest {
    private val originalSigners = "Lapp/hushmessenger/extension/MessengerSignature;->" +
        "originalSigners(Landroid/content/pm/PackageInfo;)Ljava/util/List;"

    private fun signersMethod(registers: Int, flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Trust;",
            "signers",
            emptyList(),
            "Lfixture/Signers;",
            flags,
            null,
            null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, "const/4 v0, 0x0\nreturn-object v0") }

    private fun MutableMethod.body(): List<Instruction> = implementation!!.instructions.toList()

    private fun MutableMethod.target(index: Int): Int {
        val body = body()
        var address = 0
        val addresses = body.map { instruction -> address.also { address += instruction.codeUnits } }
        return addresses.indexOf(addresses[index] + (body[index] as OffsetInstruction).codeOffset)
    }

    private val Instruction.reference get() = (this as ReferenceInstruction).reference

    @Test
    fun `the answer is read through a copy of this and Messenger's path is kept`() {
        val method = signersMethod(6)
        method.answerOriginalSigners("packageInfo", "Lfixture/Signers;")
        val body = method.body()

        assertEquals(Opcode.MOVE_OBJECT_FROM16, body[0].opcode)
        assertEquals(0, (body[0] as TwoRegisterInstruction).registerA)
        assertEquals(5, (body[0] as TwoRegisterInstruction).registerB, "this")
        assertEquals(Opcode.IGET_OBJECT, body[1].opcode)
        assertEquals(0, (body[1] as TwoRegisterInstruction).registerB)
        assertEquals("packageInfo", (body[1].reference as FieldReference).name)
        assertEquals(originalSigners, (body[2].reference as MethodReference).toString())
        assertEquals(Opcode.IF_EQZ, body[4].opcode)
        assertEquals(9, method.target(4), "null runs Messenger's own code")
        assertEquals(Opcode.RETURN_OBJECT, body[8].opcode)
        assertEquals(Opcode.CONST_4, body[9].opcode)
    }

    @Test
    fun `a method whose this sits above v15 still gets the answer`() {
        val method = signersMethod(20)
        method.answerOriginalSigners("packageInfo", "Lfixture/Signers;")
        val body = method.body()
        assertEquals(Opcode.MOVE_OBJECT_FROM16, body[0].opcode)
        assertEquals(19, (body[0] as TwoRegisterInstruction).registerB)
        assertEquals(Opcode.IGET_OBJECT, body[1].opcode)
        assertEquals(0, (body[1] as TwoRegisterInstruction).registerB)
    }

    @Test
    fun `a method with fewer than three locals stops the patch`() {
        for (registers in 1..3) {
            assertThrows(PatchException::class.java, { signersMethod(registers).answerOriginalSigners("packageInfo", "Lfixture/Signers;") },
                "$registers registers")
        }
    }

    @Test
    fun `a static method has no this to read the field from and stops the patch`() {
        val method = signersMethod(6, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)
        val before = method.body()
        val failure = assertThrows(PatchException::class.java) { method.answerOriginalSigners("packageInfo", "Lfixture/Signers;") }
        assertEquals(true, failure.message.orEmpty().contains("is static"))
        assertEquals(before, method.body())
    }

    /** FBNS's package check as Messenger writes it, cut down: the package in v0, its signers read into v2. */
    private fun fbnsCheck(read: String = "iget-object v2, v0, $SIGNATURES", jump: String = "") = fixtureMethod(
        "LX/09z;->A00(Landroid/content/Context;LX/0CQ;)V",
        """
            $jump
            iget-object v0, v8, LX/0CQ;->A01:$PACKAGE
            $read
            :after_read
            if-eqz v2, :done
            array-length v1, v2
            const-string v1, "PackageInfoUtil"
            const-string v1, "Failed to create SHA-256 hash"
            :done
            return-void
        """.trimIndent(),
        registers = 9,
        flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
    )

    /** The call and its result sit right after the read, and nothing stock moves or changes. */
    private fun assertRouted(before: List<Instruction>, after: List<Instruction>, label: String) {
        val readAt = before.indexOfFirst { it.isSignaturesRead() }
        val read = before[readAt] as TwoRegisterInstruction
        assertEquals(before.size + 2, after.size, label)
        assertEquals(before, after.filterIndexed { i, _ -> i != readAt + 1 && i != readAt + 2 }, label)
        val call = after[readAt + 1] as FiveRegisterInstruction
        assertEquals(Opcode.INVOKE_STATIC, call.opcode, label)
        assertEquals(FBNS_SIGNERS, (call as ReferenceInstruction).reference.toString(), label)
        assertEquals(listOf(2, read.registerB, read.registerA), listOf(call.registerCount, call.registerC, call.registerD), label)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, after[readAt + 2].opcode, label)
        assertEquals(read.registerA, (after[readAt + 2] as OneRegisterInstruction).registerA, label)
    }

    private fun Instruction.isSignaturesRead() = opcode == Opcode.IGET_OBJECT &&
        (reference as FieldReference).let { it.definingClass == PACKAGE && it.name == "signatures" }

    @Test
    fun `fbns hashes the signers the extension answers`() {
        val method = fbnsCheck()
        val before = method.body()
        method.routeFbnsSigners()
        assertRouted(before, method.body(), "fixture")
    }

    @Test
    fun `an fbns check the call can't be placed in stops the patch before any edit`() {
        val cases = mapOf(
            "found 2" to fbnsCheck(read = "iget-object v2, v0, $SIGNATURES\niget-object v2, v0, $SIGNATURES"),
            "found 0" to fbnsCheck(read = "const/4 v2, 0x0"),
            "can't name" to fbnsCheck(read = "iget-object v0, v0, $SIGNATURES"),
            "jumps past" to fbnsCheck(jump = "if-eqz v3, :after_read"),
        )
        for ((message, method) in cases) {
            val before = method.body()
            val failure = assertThrows(PatchException::class.java) { method.routeFbnsSigners() }
            assertEquals(true, failure.message.orEmpty().contains(message), "$message: ${failure.message}")
            assertEquals(before, method.body(), message)
        }
    }

    @Test
    fun `the fbns mirror wants the read before both strings`() {
        assertEquals(true, fbnsCheck().isFbnsPackageCheck())
        val tagFirst = fixtureMethod(
            "LX/09z;->A01(Landroid/content/pm/PackageInfo;)V",
            """
                const-string v1, "PackageInfoUtil"
                iget-object v0, p0, $SIGNATURES
                const-string v1, "Failed to create SHA-256 hash"
                return-void
            """.trimIndent(),
            registers = 3,
            flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        )
        assertEquals(false, tagFirst.isFbnsPackageCheck())
    }

    /**
     * Mirrors [FbnsPackageCheckFingerprint], whose filters match in order: a void method that reads
     * PackageInfo.signatures, then names "PackageInfoUtil", then "Failed to create SHA-256 hash".
     */
    private fun Method.isFbnsPackageCheck(): Boolean {
        if (returnType != "V") return false
        val code = implementation?.instructions?.toList() ?: return false
        fun at(index: Int) = ((code[index] as? ReferenceInstruction)?.reference as? StringReference)?.string
        val read = code.indexOfFirst { it.isSignaturesRead() }.takeIf { it >= 0 } ?: return false
        val tag = (read + 1 until code.size).firstOrNull { at(it) == "PackageInfoUtil" } ?: return false
        return (tag + 1 until code.size).any { at(it) == "Failed to create SHA-256 hash" }
    }

    @Test
    fun `every supported build routes its one fbns check`() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString()
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val checks = dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }
                .flatMap { it.methods }.filter { it.isFbnsPackageCheck() }
            assertEquals(1, checks.size, code)
            val method = MutableMethod(checks.single())
            val before = method.body()
            method.routeFbnsSigners()
            assertRouted(before, method.body(), code)
        }
    }

    private companion object {
        const val PACKAGE = "Landroid/content/pm/PackageInfo;"
        const val SIGNATURES = "$PACKAGE->signatures:[Landroid/content/pm/Signature;"
    }
}
