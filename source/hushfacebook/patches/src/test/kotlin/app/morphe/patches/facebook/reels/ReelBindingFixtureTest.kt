/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.media.resume.VIDEO_PLAYER_PARAMS
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.PatchLogCapture
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Clean up Reels' reel binding evidence on every Facebook build the bundle declares: the
 * end-of-video step hands the extension its player right after reading it, beside Play reels
 * once's hook, and every write of the Reels auto-advance controller's player hands over the
 * controller and the player. A build without the controller goes on with a warning and the
 * end-of-video step as Play reels once left it. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReelBindingFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.descriptor()

    private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference

    /** Every class in [bundle] with a method setting [field], from the dex files that name it. */
    private fun writerClasses(bundle: File, field: FieldReference): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.fieldSection.none { it.definingClass == field.definingClass && it.name == field.name }) return@forEach
            for (classDef in dex.classes) {
                if (classDef.methods.any { playerWrites(it, field).isNotEmpty() }) found += ImmutableClassDef.of(classDef)
            }
        }
        return found
    }

    private fun ClassDef.external() = !type.startsWith(EXTENSION_PACKAGE)

    @Test
    fun `each declared build reports the reel that ends and the player the controller holds`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, VIDEO_COMPLETE_ANCHOR).filter { it.external() }.distinctBy { it.type }
                val complete = videoCompletes(holders).single()
                val listener = holders.single { it.type == complete.definingClass }
                val read = playerRead(listener, complete)
                assertNotNull("$name: ${listener.type} keeps no single player that its end-of-video step reads", read)
                val playerType = read!!.field.type
                val code = complete.code()

                // Positive control: the register holds the player. The end-of-video step reads the
                // player's own fields through it further on.
                assertTrue("$name: the end-of-video step never reads a $playerType field through v${read.register}",
                    code.drop(read.at + 1).any {
                        it.field()?.definingClass == playerType && (it as? TwoRegisterInstruction)?.registerB == read.register
                    })

                val controllers = autoAdvanceControllers(
                    FixtureDex.classesHolding(bundle, AUTO_ADVANCE_IDLE).filter { it.external() }.distinctBy { it.type },
                )
                assertEquals("$name: classes holding \"$AUTO_ADVANCE_IDLE\" and \"$DID_AUTO_ADVANCE\"", 1, controllers.size)
                val controller = controllers.single()
                val field = boundPlayerField(controller, playerType)
                assertNotNull("$name: ${controller.type} keeps no single $playerType", field)
                // Positive control: the controller uses the player it keeps.
                assertTrue("$name: ${controller.type} never reads its ${field!!.name}",
                    controller.methods.any { method ->
                        method.implementation?.instructions?.any { it.opcode == Opcode.IGET_OBJECT && it.field()?.name == field.name } == true
                    })

                val writers = writerClasses(bundle, field)
                val writes = writers.flatMap { owner -> owner.methods.filter { it.name != "<init>" } }
                    .map { it to playerWrites(it, field) }.filter { it.second.isNotEmpty() }
                assertTrue("$name: nothing outside a constructor sets ${controller.type}->${field.name}", writes.isNotEmpty())
                assertTrue("$name: a write's registers above v15",
                    writes.all { (_, found) -> found.all { it.player <= 15 && it.controller <= 15 } })

                val params = FixtureDex.classes(bundle, setOf(VIDEO_PLAYER_PARAMS)).getValue(VIDEO_PLAYER_PARAMS)
                val extension = listOf(ExtensionDex.classDef(REEL_LOOP), ExtensionDex.classDef(REEL_BINDING))
                val pool = (listOf(listener, controller, params) + writers + extension).distinctBy { it.type }

                // No controller: a warning, and the end-of-video step keeps Play reels once's hook alone.
                val bare = PatchContexts.of(pool.filter { it.type != controller.type })
                reelLoopPatch.execute(bare)
                assertEquals("$name: the missing controller's warning",
                    listOf("Reel binding evidence: expected one class holding \"$AUTO_ADVANCE_IDLE\" and \"$DID_AUTO_ADVANCE\", " +
                        "found 0. Debug reports go on without the reel binding lines."),
                    PatchLogCapture.warnings { reelBindingPatch.execute(bare) })
                assertEquals("$name: the end-of-video step changed without its controller", code.size + 2,
                    bare.mutableClassDefBy(complete.definingClass).methods.single { it.descriptor() == complete.descriptor() }.code().size)

                val context = PatchContexts.of(pool)
                reelLoopPatch.execute(context)
                assertEquals("$name: warned", emptyList<String>(), PatchLogCapture.warnings { reelBindingPatch.execute(context) })

                val patched = context.mutableClassDefBy(complete.definingClass).methods
                    .single { it.descriptor() == complete.descriptor() }.code()
                assertEquals("$name: Play reels once's two instructions and this one", code.size + 3, patched.size)
                assertEquals("$name: Facebook's player read stays where it was", Opcode.IGET_OBJECT, patched[read.at].opcode)
                val ended = patched[read.at + 1]
                assertEquals("$name: the read is followed by $BINDING_ENDED", BINDING_ENDED, ended.called())
                assertEquals("$name: handed the player alone",
                    listOf(read.register, 1),
                    listOf((ended as RegisterRangeInstruction).startRegister, ended.registerCount))
                val loop = patched.indexOfFirst { it.called() == LOOPS }
                assertEquals("$name: Play reels once's hook still follows its read", Opcode.IGET_BOOLEAN, patched[loop - 1].opcode)

                for ((method, found) in writes) {
                    val after = context.mutableClassDefBy(method.definingClass).methods
                        .single { it.descriptor() == method.descriptor() }
                    // Nops aside: the assembler may add one to keep a switch payload aligned.
                    assertEquals("$name: one instruction per write in ${method.descriptor()}",
                        method.code().count { it.opcode != Opcode.NOP } + found.size,
                        after.code().count { it.opcode != Opcode.NOP })
                    val kept = playerWrites(after, field)
                    assertEquals("$name: Facebook's writes stay in ${method.descriptor()}",
                        found.map { listOf(it.player, it.controller) }, kept.map { listOf(it.player, it.controller) })
                    for (write in kept) {
                        val bound = after.code()[write.at + 1]
                        assertEquals("$name: the write is followed by $BINDING_BOUND", BINDING_BOUND, bound.called())
                        assertEquals("$name: handed the controller, then the player",
                            listOf(2, write.controller, write.player),
                            listOf((bound as FiveRegisterInstruction).registerCount, bound.registerC, bound.registerD))
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has both hooks`() {
        val binding = ExtensionDex.classDef(REEL_BINDING)
        assertEquals("bound's shape", BINDING_BOUND, binding.methods.singleOrNull { it.name == "bound" }?.descriptor())
        assertEquals("ended's shape", BINDING_ENDED, binding.methods.singleOrNull { it.name == "ended" }?.descriptor())
    }
}
