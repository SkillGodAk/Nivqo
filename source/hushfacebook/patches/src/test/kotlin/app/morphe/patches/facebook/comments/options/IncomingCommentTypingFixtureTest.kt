/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.options

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comment sheet options' incoming typing hook on every Facebook build the bundle declares (#170):
 * the class named CommentTypingDetected is found by its kept string, the one handler that builds it
 * from an int is found by its shape, and right after the `iget` that filled the count register the
 * extension is asked, as a range call, and its answer goes back into the same register. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it. Redex names differ between
 * the arm64 and armv7 bundles, so nothing here names a class.
 */
class IncomingCommentTypingFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    @Test
    fun `each declared build's typer count goes through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val events = FixtureDex.classesHolding(bundle, "CommentTypingDetected")
                    .filter { owner -> owner.methods.any { it.name == "<init>" && holdsString(it, "CommentTypingDetected") } }
                assertEquals("$name: classes named CommentTypingDetected", 1, events.size)
                val event = events.single()

                // Positive control: the second kept string, in the event's toString, sits in the same class.
                assertTrue("$name: the event's toString lacks its typerCount text",
                    event.methods.any { holdsString(it, "CommentTypingDetected(typerCount=") })

                val handlers = FixtureDex.methodsWhere(
                    bundle,
                    { dex -> dex.methodSection.any { it.definingClass == event.type && it.name == "<init>" } },
                ) { method ->
                    method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;") &&
                        method.returnType == "V" && method.implementation?.instructions?.any {
                            it.opcode == Opcode.INVOKE_DIRECT && it.called()?.startsWith("${event.type}-><init>(I)V") == true
                        } == true
                }
                assertTrue("$name: no handler builds the event", handlers.isNotEmpty())
                val owners = FixtureDex.classes(bundle, handlers.map { it.definingClass }.toSet()).values.toList()

                val found = incomingTyperCount(owners + event)
                val before = found.method.code()
                assertEquals("$name: the count is an int field read", Opcode.IGET, before[found.index].opcode)

                val context = PatchContexts.of(owners + event)
                incomingCommentTypingPatch.execute(context)
                val patched = context.mutableClassDefBy(found.method.definingClass).methods.single {
                    it.name == found.method.name && it.returnType == found.method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == found.method.parameterTypes.map(CharSequence::toString)
                }.code()
                assertEquals("$name: two instructions added", before.size + 2, patched.size)
                assertEquals("$name: the iget stays", before[found.index].opcode, patched[found.index].opcode)
                assertEquals("$name: the extension is asked as a range call", Opcode.INVOKE_STATIC_RANGE, patched[found.index + 1].opcode)
                assertEquals("$name: it asks $INCOMING_TYPERS", INCOMING_TYPERS, patched[found.index + 1].called())
                assertEquals("$name: its answer goes back", Opcode.MOVE_RESULT, patched[found.index + 2].opcode)
                assertEquals("$name: the rest is Facebook's own",
                    before.drop(found.index + 1).map { it.opcode }, patched.drop(found.index + 3).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hook`() {
        val hook = ExtensionDex.classDef(COMMENT_TYPING_INCOMING).methods.singleOrNull { it.name == "incomingTypers" }
        assertTrue("the extension has no CommentSheetOptions.incomingTypers", hook != null)
        assertEquals("incomingTypers' shape", INCOMING_TYPERS, hook!!.descriptor())
    }
}
