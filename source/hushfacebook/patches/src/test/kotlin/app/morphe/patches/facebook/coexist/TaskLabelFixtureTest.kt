/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * App name on the recent apps screen, on every Facebook build the bundle declares (#105): Facebook
 * makes its main task's description from a label, and the swap goes in right before that
 * constructor, handing the label through the extension and back in the same register. Nothing of
 * Facebook's code moves. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class TaskLabelFixtureTest {
    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    private fun makesTaskDescription(classDef: ClassDef) = classDef.methods.any { method ->
        method.implementation?.instructions?.any {
            it.reference()?.startsWith("Landroid/app/ActivityManager\$TaskDescription;-><init>(Ljava/lang/String;") == true
        } == true
    }

    @Test
    fun `each declared build hands its task's label through App name`() = bundles { bundle ->
        val name = bundle.name
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (!classDef.type.startsWith("Lapp/morphe/") && makesTaskDescription(classDef)) holders += ImmutableClassDef.of(classDef)
            }
        }
        val labels = holders.flatMap { taskLabels(it) }
        assertTrue("$name makes no task description from a label", labels.isNotEmpty())
        assertTrue("$name: none of them is the main task's, set with setTaskDescription", labels.any { label ->
            label.method.code().any { it.reference() == "Landroid/app/Activity;->setTaskDescription(Landroid/app/ActivityManager\$TaskDescription;)V" }
        })

        val context = PatchContexts.of(holders)
        for (label in labels.sortedByDescending { it.at }) {
            val where = "$name: ${label.method.definingClass}->${label.method.name}"
            val original = label.method.code()
            val method = context.mutableClassDefBy(label.method.definingClass).methods.single {
                it.name == label.method.name && it.returnType == label.method.returnType &&
                    it.parameterTypes.map(CharSequence::toString) == label.method.parameterTypes.map(CharSequence::toString)
            }
            method.labelsTaskWithAppName(label.at, label.label)
            val patched = method.code()
            val at = label.at
            assertEquals("$where gains two instructions", original.size + 2, patched.size)
            assertEquals("$where: the label goes to the extension", Opcode.INVOKE_STATIC, patched[at].opcode)
            assertEquals("$where: the label goes to the extension", TASK_LABEL, patched[at].reference())
            assertEquals("$where: the label itself", label.label, (patched[at] as FiveRegisterInstruction).registerC)
            assertEquals("$where: and comes back", Opcode.MOVE_RESULT_OBJECT, patched[at + 1].opcode)
            assertEquals("$where: into the same register", label.label, (patched[at + 1] as OneRegisterInstruction).registerA)
            assertEquals("$where: the constructor takes it", label.label, (patched[at + 2] as FiveRegisterInstruction).registerD)
            assertEquals("$where: Facebook's code stays", original.map { it.opcode },
                patched.take(at).map { it.opcode } + patched.drop(at + 2).map { it.opcode })
        }
    }
}
