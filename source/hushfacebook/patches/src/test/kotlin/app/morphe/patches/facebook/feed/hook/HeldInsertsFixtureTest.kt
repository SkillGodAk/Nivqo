/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.hook

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The held rows hooks on every Facebook build the bundle declares: exactly one class holding the
 * RecyclerBinder's applyReadyBatches trace answers the hook's rule, its row holder and Litho's
 * ComponentTree give the helper its reads, and the patch adds the helper to the holder and puts the
 * extension's answer in front of the if-nez on the holder's own, with the rest of applyReadyBatches
 * left as it was. Exactly one commit step holding Litho's dropped layout log calls the same layout
 * test, and the patch swaps that call for a helper added to ComponentTree and changes nothing else.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class HeldInsertsFixtureTest {
    private fun Method.body(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.call(): String? = ((this as? ReferenceInstruction)?.reference)?.toString()

    private fun Instruction.registers(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

    @Test
    fun `each declared build lets a row in that only an accessibility change holds back`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, APPLY_READY_BATCHES)
                val sites = owners.mapNotNull(::heldInsertSite)
                assertEquals("$name: RecyclerBinders among ${owners.map { it.type }}", 1, sites.size)
                val site = sites.single()
                val holderType = site.layoutCheck.definingClass
                val classes = FixtureDex.classes(bundle, setOf(holderType, COMPONENT_TREE))
                val holder = classes.getValue(holderType)
                val parts = laidOutParts(holder, site.layoutCheck, classes.getValue(COMPONENT_TREE))
                assertNotNull("$name: the helper's reads in ${site.layoutCheck}", parts)
                parts!!
                assertEquals("$name: the layouts are the tree's", setOf(COMPONENT_TREE), parts.layouts.map { it.definingClass }.toSet())
                assertEquals("$name: the size test is the layout's", parts.layouts[0].type, parts.fits.definingClass)

                // Litho's commit step runs the same layout test on a layout it finished.
                val tree = classes.getValue(COMPONENT_TREE)
                val dropOwners = FixtureDex.classesHolding(bundle, LAYOUT_DROPPED)
                val drops = dropOwners.mapNotNull { droppedLayoutSite(it, tree) }
                assertEquals("$name: commit steps among ${dropOwners.map { it.type }}", 1, drops.size)
                val drop = drops.single()
                assertEquals("$name: the commit's size test is the holder's", parts.fits, drop.fits)

                val before = site.method.body()
                val commitBefore = drop.method.body()
                val context = PatchContexts.of(
                    (owners.filter { it.type != holderType } + holder + tree + dropOwners).distinctBy { it.type },
                )
                heldInsertsPatch.execute(context)

                // The helper: static, takes the holder, and reads the tree, both specs, both layouts
                // and the layout's size test, which never compares the accessibility state.
                val helper = context.mutableClassDefBy(holderType).methods.single { it.name == LAID_OUT_HELPER }
                assertTrue("$name: the helper is static", AccessFlags.STATIC.isSet(helper.accessFlags))
                assertEquals("$name: the helper's parameters", listOf(holderType), helper.parameterTypes.map(CharSequence::toString))
                val reads = helper.body().mapNotNull { it.call() }
                for (field in listOf(parts.tree, parts.width, parts.height) + parts.layouts) {
                    assertTrue("$name: the helper reads $field", field.toString() in reads)
                }
                assertEquals("$name: the helper tests each layout", 2, reads.count { it == parts.fits.toString() })

                val patched = context.mutableClassDefBy(site.method.definingClass).methods.single {
                    it.name == site.method.name &&
                        it.parameterTypes.map(CharSequence::toString) == site.method.parameterTypes.map(CharSequence::toString)
                }
                val after = patched.body()
                val added = after.subList(site.index, site.index + 6)
                assertEquals(
                    "$name: the hook",
                    listOf(Opcode.IF_NEZ, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT),
                    added.map { it.opcode },
                )
                assertEquals("$name: the holder read again", site.holderField.toString(), added[1].call())
                assertEquals("$name: the helper", "$holderType->$LAID_OUT_HELPER($holderType)Z", added[2].call())
                assertEquals("$name: the extension", LET_IN, added[4].call())
                assertEquals("$name: one ask", 1, after.count { it.call() == LET_IN })
                assertEquals("$name: the holder's own if-nez after it", Opcode.IF_NEZ, after[site.index + 6].opcode)

                // A holder with its layout skips the hook and goes straight to its own if-nez.
                val skip = ControlFlow.of(patched).normal[site.index].single { it != site.index + 1 }
                assertEquals("$name: where a laid out holder goes", site.index + 6, skip)
                assertEquals("$name: the rest of applyReadyBatches", before.map { it.opcode },
                    after.filterIndexed { index, _ -> index !in site.index until site.index + 6 }.map { it.opcode })

                // The tree's helper: the test's own answer, then the size test, then the extension.
                val committable = context.mutableClassDefBy(COMPONENT_TREE).methods.single { it.name == COMMITTABLE_HELPER }
                assertTrue("$name: the tree's helper is static", AccessFlags.STATIC.isSet(committable.accessFlags))
                assertEquals("$name: the tree's helper takes the test's parameters",
                    drop.test.parameterTypes.map(CharSequence::toString), committable.parameterTypes.map(CharSequence::toString))
                assertEquals("$name: what the tree's helper calls",
                    listOf(drop.test.toString(), drop.fits.toString(), KEEP_LAYOUT), committable.body().mapNotNull { it.call() })

                // The commit step calls the helper instead, on the same registers, and nothing else moves.
                val commit = context.mutableClassDefBy(drop.method.definingClass).methods.single {
                    it.name == drop.method.name && it.returnType == drop.method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == drop.method.parameterTypes.map(CharSequence::toString)
                }
                val commitAfter = commit.body()
                assertEquals("$name: the commit's call", committableCall(drop), commitAfter[drop.index].call())
                assertEquals("$name: on the same registers", commitBefore[drop.index].registers(), commitAfter[drop.index].registers())
                assertEquals("$name: the commit's opcodes", commitBefore.map { it.opcode }, commitAfter.map { it.opcode })
                assertEquals("$name: the commit's other references",
                    commitBefore.filterIndexed { index, _ -> index != drop.index }.map { it.call() },
                    commitAfter.filterIndexed { index, _ -> index != drop.index }.map { it.call() })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
