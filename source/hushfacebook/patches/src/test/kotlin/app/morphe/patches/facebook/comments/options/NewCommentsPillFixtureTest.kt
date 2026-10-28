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
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comment sheet options' new comments pill hook on every Facebook build the bundle declares (#185):
 * across the whole APK exactly one comment list observer writes the pill's text, and right after
 * the plural helper hands that text back the extension is asked, as a range call, and its answer
 * goes back into the same register ahead of Facebook's own empty-text check. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it. Redex names differ between the arm64
 * and armv7 bundles, so nothing here names a class.
 */
class NewCommentsPillFixtureTest {
    private val layout = "Landroidx/recyclerview/widget/LinearLayoutManager;"

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.called() = ((this as? ReferenceInstruction)?.reference as? MethodReference)

    @Test
    fun `each declared build's new comments text goes through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                // Every (II)V method in the APK that both scrolls a list to an offset and asks for
                // its last fully shown row, which is where the finder looks.
                val observers = FixtureDex.methodsWhere(
                    bundle,
                    { dex -> dex.methodSection.any { it.definingClass == layout && it.name == "scrollToPositionWithOffset" } },
                ) { method ->
                    method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) == listOf("I", "I") &&
                        method.implementation?.instructions?.let { code ->
                            code.any { it.called()?.name == "findLastCompletelyVisibleItemPosition" } &&
                                code.any { it.called()?.name == "scrollToPositionWithOffset" }
                        } == true
                }
                assertTrue("$name: no list observer scrolls to new comments", observers.isNotEmpty())
                val owners = FixtureDex.classes(bundle, observers.map { it.definingClass }.toSet()).values.toList()

                val found = newCommentsPillText(owners)
                val before = found.method.code()
                assertEquals("$name: the text is kept by a move-result-object", Opcode.MOVE_RESULT_OBJECT, before[found.index].opcode)
                // Positive control: the call before it is the plural helper, and the next few
                // instructions measure the text, which is the check that skips an empty pill.
                val helper = before[found.index - 1].called()
                assertEquals("$name: the text comes from a (Resources, int, int) helper",
                    listOf("Landroid/content/res/Resources;", "I", "I"), helper?.parameterTypes?.map(CharSequence::toString))
                assertTrue("$name: nothing checks the text's length after it",
                    before.drop(found.index + 1).take(3).any { it.called()?.let { call -> call.definingClass == "Ljava/lang/String;" && call.name == "length" } == true })

                val context = PatchContexts.of(owners)
                context.hookNewCommentsPill(owners)
                val patched = context.mutableClassDefBy(found.method.definingClass).methods.single {
                    it.name == found.method.name && it.returnType == found.method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == found.method.parameterTypes.map(CharSequence::toString)
                }.code()
                assertEquals("$name: two instructions added", before.size + 2, patched.size)
                assertEquals("$name: the move-result stays", before[found.index].opcode, patched[found.index].opcode)
                assertEquals("$name: the extension is asked as a range call", Opcode.INVOKE_STATIC_RANGE, patched[found.index + 1].opcode)
                assertEquals("$name: it asks $NEW_COMMENTS_PILL", NEW_COMMENTS_PILL, patched[found.index + 1].called()?.toString())
                assertEquals("$name: its answer goes back", Opcode.MOVE_RESULT_OBJECT, patched[found.index + 2].opcode)
                assertEquals("$name: the rest is Facebook's own",
                    before.drop(found.index + 1).map { it.opcode }, patched.drop(found.index + 3).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The newer pill (#185, 2026-10-19): across the whole APK exactly one static method keeps the
     * builder's name and takes the comment that came in, it builds the pill's data from the plural
     * helper and returns it once, and the patch asks the extension right before that return, as a
     * range call, with the answer going back into the register the return reads.
     */
    @Test
    fun `each declared build's newer pill data goes through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, PILL_DATA_NAME)
                assertTrue("$name: nothing keeps \"$PILL_DATA_NAME\"", owners.isNotEmpty())
                val found = newCommentsPillData(owners)
                val before = found.method.code()
                assertEquals("$name: the builder returns its data once", 1, found.returns.size)
                val (index, register) = found.returns.single()
                assertEquals(Opcode.RETURN_OBJECT, before[index].opcode)
                // Positive controls: the builder writes the pill's text with the quantity string
                // and makes the data it returns in the method itself.
                assertTrue("$name: the builder doesn't write a plural text",
                    before.any { it.called()?.let { call -> call.definingClass == "Landroid/content/res/Resources;" && call.name == "getQuantityString" } == true })
                assertTrue("$name: the builder doesn't make what it returns",
                    before.any { it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).reference.toString() == found.method.returnType })

                val context = PatchContexts.of(owners)
                context.hookNewCommentsPillData(owners)
                val patched = context.mutableClassDefBy(found.method.definingClass).methods.single {
                    it.name == found.method.name && it.returnType == found.method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == found.method.parameterTypes.map(CharSequence::toString)
                }.code()
                assertEquals("$name: three instructions added", before.size + 3, patched.size)
                assertEquals("$name: the extension is asked as a range call", Opcode.INVOKE_STATIC_RANGE, patched[index].opcode)
                assertEquals("$name: it asks $NEW_COMMENTS_PILL_DATA", NEW_COMMENTS_PILL_DATA, patched[index].called()?.toString())
                assertEquals("$name: it hands over the data", listOf(register),
                    (patched[index] as RegisterRangeInstruction).let { (it.startRegister until it.startRegister + it.registerCount).toList() })
                assertEquals("$name: its answer goes back", Opcode.MOVE_RESULT_OBJECT, patched[index + 1].opcode)
                assertEquals("$name: into the register the return reads", register, (patched[index + 1] as OneRegisterInstruction).registerA)
                // The extension answers an Object. Returned as is from a builder declared to return
                // the pill's own type, ART rejects the whole class and every comment sheet crashes
                // (0.16.0, #203, #204), so the answer is cast back first.
                assertEquals("$name: the answer is cast back", Opcode.CHECK_CAST, patched[index + 2].opcode)
                assertEquals("$name: to the type the builder declares", found.method.returnType,
                    (patched[index + 2] as ReferenceInstruction).reference.toString())
                assertEquals("$name: in the register the return reads", register, (patched[index + 2] as OneRegisterInstruction).registerA)
                assertEquals("$name: the return stays", Opcode.RETURN_OBJECT, patched[index + 3].opcode)
                assertEquals("$name: the rest is Facebook's own",
                    before.map { it.opcode }, patched.take(index).map { it.opcode } + patched.drop(index + 3).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hooks`() {
        val hook = ExtensionDex.classDef(COMMENT_SHEET_OPTIONS).methods.singleOrNull { it.name == "newCommentsPill" }
        assertTrue("the extension has no CommentSheetOptions.newCommentsPill", hook != null)
        assertEquals("newCommentsPill's shape", NEW_COMMENTS_PILL, hook!!.descriptor())
        val data = ExtensionDex.classDef(COMMENT_SHEET_OPTIONS).methods.singleOrNull { it.name == "newCommentsPillData" }
        assertTrue("the extension has no CommentSheetOptions.newCommentsPillData", data != null)
        assertEquals("newCommentsPillData's shape", NEW_COMMENTS_PILL_DATA, data!!.descriptor())
    }
}
