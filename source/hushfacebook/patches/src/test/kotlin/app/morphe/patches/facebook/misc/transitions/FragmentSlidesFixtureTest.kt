/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.transitions

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Turn off screen transitions' inner screens on every Facebook build the bundle declares. One
 * method prints a fragment transaction's four custom animations by their androidx labels, all four
 * from fields of the transaction BackStackRecord extends; the transaction has one (IIII)V method
 * keeping its arguments in them, which does nothing else; and another of its methods copies the
 * four into each op. Then the patch: each argument goes through the extension before it's kept.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FragmentSlidesFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private val Instruction.field: FieldReference?
        get() = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun MethodReference.key() = definingClass + "->" + name + parameterTypes.joinToString("", "(", ")") + returnType

    @Before
    @After
    fun forgetTheLastMatch() {
        BackStackDumpFingerprint.clearMatch()
    }

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    @Test
    fun `each declared build hands a fragment its animations through one setter, and the patch asks before it keeps them`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name

                val records = FixtureDex.classesHolding(bundle, ENTER_ANIM_LOG).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val dumps = records.flatMap { owner -> owner.methods.filter { holdsString(it, ENTER_ANIM_LOG) } }
                assertEquals("$name: methods printing $ENTER_ANIM_LOG", 1, dumps.size)
                val dump = dumps.single()
                assertTrue("$name: the dump also prints $POP_ENTER_ANIM_LOG", holdsString(dump, POP_ENTER_ANIM_LOG))
                assertEquals("$name: the dump's shape", listOf("Ljava/io/PrintWriter;", "Ljava/lang/String;", "Z"), dump.parameterTypes.map(CharSequence::toString))
                val record = records.single { it.type == dump.definingClass }

                val fields = fragmentAnimationFields(dump)
                val type = fields.first().definingClass
                assertEquals("$name: BackStackRecord extends the transaction holding the four", type, record.superclass)
                val transaction = FixtureDex.classes(bundle, setOf(type)).getValue(type)
                val setters = transaction.methods.filter { it.keepsAnimations(fields) }
                assertEquals("$name: setCustomAnimations in $type", 1, setters.size)
                val setter = setters.single()
                val setterCode = setter.code()
                assertEquals("$name: setCustomAnimations only keeps the four", listOf(Opcode.IPUT, Opcode.IPUT, Opcode.IPUT, Opcode.IPUT, Opcode.RETURN_VOID),
                    setterCode.map { it.opcode })
                // addOp: the four go into each op in the same order, so an op added after the setter carries what it kept.
                val copies = transaction.methods.filter { method ->
                    val reads = method.implementation?.instructions?.filter { it.opcode == Opcode.IGET }?.mapNotNull { it.field }.orEmpty()
                    reads.map { it.name } == fields.map { it.name } && reads.all { it.definingClass == type }
                }
                assertEquals("$name: methods copying the four into an op", 1, copies.size)
                val opFields = copies.single().code().filter { it.opcode == Opcode.IPUT }.mapNotNull { it.field }
                assertEquals("$name: the op takes all four", 4, opFields.size)
                assertEquals("$name: into one op class", 1, opFields.map { it.definingClass }.toSet().size)
                println("$name: ${dump.definingClass}->${dump.name} prints ${fields.joinToString { it.name }} of $type, set by ${setter.name}, " +
                    "copied into ${opFields.first().definingClass} by ${copies.single().name}")

                val context = PatchContexts.of(listOf(record, transaction))
                forgetTheLastMatch()
                with(context) { quietFragmentSlides(BackStackDumpFingerprint.method) }

                val patched = context.mutableClassDefBy(type).methods.single { it.name == setter.name && it.keepsAnimations(fields) }
                val code = patched.code()
                assertEquals("$name: eight instructions before Facebook's", setterCode.size + 8, code.size)
                val first = setter.implementation!!.registerCount - 4
                for (argument in 0..3) {
                    val ask = code[argument * 2] as Instruction35c
                    assertEquals("$name: the call for argument ${argument + 1}", FRAGMENT_ANIMATION, ask.call!!.key())
                    assertEquals("$name: the call passes argument ${argument + 1} alone", 1, ask.registerCount)
                    assertEquals("$name: the call passes argument ${argument + 1}", first + argument, ask.registerC)
                    val answer = code[argument * 2 + 1]
                    assertEquals("$name: the answer", Opcode.MOVE_RESULT, answer.opcode)
                    assertEquals("$name: argument ${argument + 1} keeps the answer", first + argument, (answer as OneRegisterInstruction).registerA)
                }
                for (index in setterCode.indices) {
                    assertEquals("$name: Facebook's own at $index", setterCode[index].opcode, code[index + 8].opcode)
                }
                val kept = code.drop(8).filter { it.opcode == Opcode.IPUT }.map { (it as TwoRegisterInstruction).registerA to it.field!!.name }
                assertEquals("$name: the answers are what's kept", (0..3).map { first + it to fields[it].name }, kept)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    @Test
    fun `a dump that prints no animations refuses, naming the method`() {
        val method = ImmutableMethod("LX/0Aa;", "A0V", null, "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(4, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null))
        val failure = assertThrows(PatchException::class.java) { fragmentAnimationFields(method) }
        assertEquals("$PATCH: LX/0Aa;->A0V doesn't print \"$ENTER_ANIM_LOG\"", failure.message)
    }
}
