/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #206: on AMOLED a post with no photo ran into the next one, since the gap between feed rows
 * shows the row's background and AMOLED makes it black. Each row is a LithoView, which draws through
 * Litho's ComponentHost.dispatchDraw, so on each declared build that method has to hand the host and
 * its canvas to FeedGaps.drawRow after it has drawn everything, its drawables included, so the line
 * goes over anything Litho mounts in the row.
 *
 * Read from 583 (2026-10-21): LithoView extends BaseMountingView, which extends ComponentHost, whose
 * final dispatchDraw (8 registers, one return-void that a tracing check branches to) is the only one
 * on the way down to ViewGroup's.
 */
class FeedGapsFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun bundles(version: String) = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    @Test
    fun `each Litho row hands its canvas to FeedGaps once it has drawn, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                val classes = FixtureDex.classes(bundle, setOf(COMPONENT_HOST, MOUNTING_VIEW, LITHO_VIEW))
                val host = classes.getValue(COMPONENT_HOST)

                // A row is a LithoView, and nothing between it and the host draws its own way.
                assertEquals("$name: LithoView extends BaseMountingView", MOUNTING_VIEW, classes.getValue(LITHO_VIEW).superclass)
                assertEquals("$name: which extends ComponentHost", COMPONENT_HOST, classes.getValue(MOUNTING_VIEW).superclass)
                for (between in listOf(LITHO_VIEW, MOUNTING_VIEW)) {
                    assertTrue("$name: $between draws through the host's dispatchDraw",
                        classes.getValue(between).methods.none { it.name == "dispatchDraw" })
                }
                assertTrue("$name: the host is a ViewGroup", isViewGroup(bundle, host))

                val draw = host.methods.single(::isHostDraw)
                val registers = draw.implementation!!.registerCount
                val returns = draw.body().count { it.opcode == Opcode.RETURN_VOID }
                assertTrue("$name: dispatchDraw returns", returns > 0)
                val context = PatchContexts.of(listOf(host))
                with(context) { hookFeedGaps() }
                val after = context.mutableClassDefBy(COMPONENT_HOST).methods.single(::isHostDraw).body()

                val hooks = after.indices.filter { after[it].calls() == FEED_GAP_ROW }
                assertEquals("$name: one drawRow per return", returns, hooks.size)
                assertEquals("$name: and nothing else added", draw.body().size + returns, after.size)
                for (at in hooks) {
                    val hook = after[at]
                    assertEquals("$name: drawRow at $at is a range call", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
                    assertEquals("$name: from p0, the host", registers - 2, (hook as RegisterRangeInstruction).startRegister)
                    assertEquals("$name: and p1, its canvas", 2, hook.registerCount)
                    assertEquals("$name: right before a return", Opcode.RETURN_VOID, after[at + 1].opcode)
                }
                // Over the row's background: after ViewGroup's dispatchDraw, where Litho draws the
                // drawables beside the children, and after the drawables it draws on top of them.
                val lastDraw = after.indices.last { after[it].calls()?.let { it.endsWith("->dispatchDraw($CANVAS)V") || it == DRAWABLE_DRAW } == true }
                assertTrue("$name: drawRow comes after the last of the host's own drawing", hooks.all { it > lastDraw })
                assertTrue("$name: no branch lands on a return past drawRow", branchTargets(after).none { after[it].opcode == Opcode.RETURN_VOID })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The method [this] calls, or null when it calls none. */
    private fun Instruction.calls(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /** Whether [host]'s superclasses reach ViewGroup, so the verifier takes it for drawRow's first parameter. */
    private fun isViewGroup(bundle: File, host: ClassDef): Boolean {
        var type = host.superclass
        repeat(4) {
            if (type == VIEW_GROUP) return true
            type = FixtureDex.classes(bundle, setOfNotNull(type))[type]?.superclass ?: return false
        }
        return false
    }

    private companion object {
        const val MOUNTING_VIEW = "Lcom/facebook/litho/BaseMountingView;"
        const val LITHO_VIEW = "Lcom/facebook/litho/LithoView;"
        const val VIEW_GROUP = "Landroid/view/ViewGroup;"
        const val CANVAS = "Landroid/graphics/Canvas;"
        const val DRAWABLE_DRAW = "Landroid/graphics/drawable/Drawable;->draw($CANVAS)V"
    }
}
