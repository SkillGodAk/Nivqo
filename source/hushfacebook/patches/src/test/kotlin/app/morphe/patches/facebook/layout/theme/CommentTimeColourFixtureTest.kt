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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The older comment row's time (#138) on every Facebook build the bundle declares: TimestampPlugin
 * keeps its name, has one static method building the time's SpannableString, and that method reads
 * one theme colour with a static (Context, int, int) int call whose answer goes into the span. The
 * hook puts the extension right after that read on the same register, as a range call, and a second
 * call to the hook, the other theme's, leaves it as it is. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class CommentTimeColourFixtureTest {
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

    private fun Any.reference() = ((this as? ReferenceInstruction)?.reference)?.toString()

    @Test
    fun `the time's colour goes through the extension on every declared build`() = bundles { bundle ->
        val name = bundle.name
        val plugin = FixtureDex.classes(bundle, setOf(COMMENT_TIME_PLUGIN))[COMMENT_TIME_PLUGIN]
        assertTrue("$name has no $COMMENT_TIME_PLUGIN", plugin != null)
        val builder = commentTimeBuilder(plugin!!.methods)
        val original = builder.implementation!!.instructions.toList()
        val reads = commentTimeColourReads(builder)
        assertEquals("$name: one theme colour read", 1, reads.size)
        val read = reads.single()
        val call = original[read - 1].let { (it as ReferenceInstruction).reference as MethodReference }
        assertEquals("$name: the read is handed the row's context and two ints",
            listOf("Landroid/content/Context;", "I", "I"), call.parameterTypes.map { it.toString() })
        val register = (original[read] as OneRegisterInstruction).registerA
        // The read's answer is the span's colour: the span's constructor takes it after the read.
        val span = original.drop(read + 1).indexOfFirst { instruction ->
            instruction.opcode == Opcode.INVOKE_DIRECT && instruction.reference()?.contains("-><init>(Ljava/lang/String;I)V") == true
        }
        assertTrue("$name: no span takes the colour after the read", span >= 0)

        val context = PatchContexts.of(listOf(plugin))
        context.hookCommentTimeColour()
        context.hookCommentTimeColour()
        val method = context.mutableClassDefBy(COMMENT_TIME_PLUGIN).methods.single { it.name == builder.name }
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$name: two instructions added, once", original.size + 2, patched.size)
        val hook = patched[read + 1]
        assertEquals("$name: a range call", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals("$name: to the extension", COMMENT_TIME, hook.reference())
        val range = hook as RegisterRangeInstruction
        assertEquals("$name: on the read's register", listOf(register, 1), listOf(range.startRegister, range.registerCount))
        assertEquals("$name: the answer is kept", Opcode.MOVE_RESULT, patched[read + 2].opcode)
        assertEquals("$name: in the same register", register, (patched[read + 2] as OneRegisterInstruction).registerA)
        assertEquals("$name: Facebook's code stays", original.map { it.opcode },
            patched.take(read + 1).map { it.opcode } + patched.drop(read + 3).map { it.opcode })
    }
}
