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
import app.morphe.util.RegisterLiveness
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tail fetch hook on every Facebook build the bundle declares: exactly one class holding the
 * scroll fetcher's onScroll trace answers the hook's rule, and the patch puts the extension's ask in
 * front of onScroll's branch that doesn't fetch, on registers nothing reads, with its yes going to
 * the first instruction of Facebook's own ask and the rest of onScroll left as it was. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class TailFetchFixtureTest {
    private fun Method.body(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.call(): String? = ((this as? ReferenceInstruction)?.reference)?.toString()

    @Test
    fun `each declared build has one scroll fetcher, and its no-fetch branch can ask again`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, SCROLL_FETCHER_TRACE)
                val sites = owners.mapNotNull(::tailFetchSite)
                assertEquals("$name: scroll fetchers among ${owners.map { it.type }}", 1, sites.size)
                val site = sites.single()
                val before = site.method.body()
                assertEquals("$name: the ask opens by reading the feed loader", Opcode.IGET_OBJECT, before[site.ask].opcode)
                assertTrue("$name: the ask comes after the branch", site.ask > site.index)

                val context = PatchContexts.of(owners)
                homeTailFetchPatch.execute(context)
                val patched = context.mutableClassDefBy(site.method.definingClass).methods.single {
                    it.name == site.method.name &&
                        it.parameterTypes.map(CharSequence::toString) == site.method.parameterTypes.map(CharSequence::toString)
                }
                val after = patched.body()
                val added = after.subList(site.index, site.index + 5)
                assertEquals("$name: the hook's reads", listOf(Opcode.IGET, Opcode.IGET), added.take(2).map { it.opcode })
                assertEquals("$name: the last row", site.lastRow.toString(), added[0].call())
                assertEquals("$name: the rows", site.rows.toString(), added[1].call())
                assertEquals("$name: the ask", ASK_AGAIN, added[2].call())
                assertEquals("$name: one ask", 1, after.count { it.call() == ASK_AGAIN })
                assertEquals("$name: its yes", Opcode.IF_NEZ, added[4].opcode)

                // Its yes lands on Facebook's own ask, which moved down by the five added instructions.
                val target = ControlFlow.of(patched).normal[site.index + 4].single { it != site.index + 5 }
                assertEquals("$name: where its yes goes", site.ask + 5, target)
                assertEquals("$name: the instruction there", before[site.ask].call(), after[target].call())

                val borrowed = (added[2] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) }
                val liveness = RegisterLiveness.of(site.method)
                val live = liveness.liveInto(site.index) + liveness.liveInto(site.ask)
                assertTrue("$name: the hook borrows $borrowed, which onScroll still reads: $live",
                    borrowed.none { it in live } && borrowed.all { it <= 15 })
                assertEquals("$name: the rest of onScroll", before.map { it.opcode },
                    after.filterIndexed { index, _ -> index !in site.index until site.index + 5 }.map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
