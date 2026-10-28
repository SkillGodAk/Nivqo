/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.transitions

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Turn off screen transitions' photo page on every Facebook build the bundle declares. The page a
 * multi-photo post's photo opens on grows the photo with one static animator method that takes its
 * length as a Long, and its open, close and slide-away methods hand each animator and the
 * background's fade a long length. The patch has the Long go through the extension as the zoom
 * starts, and each long right before the call it's handed to, back in the same pair. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it; the tests on a page made by
 * hand run either way.
 */
class PhotoPageFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun MethodReference.key() = definingClass + "->" + name + parameterTypes.joinToString("", "(", ")") + returnType

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    @Test
    fun `each declared build moves the photo page for a length, and the patch has each length go through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name

                val page = FixtureDex.classes(bundle, setOf(PHOTO_PAGE))[PHOTO_PAGE] ?: throw AssertionError("$name: no $PHOTO_PAGE")
                val reference = photoPageZoom(page)
                val zoomClass = FixtureDex.classes(bundle, setOf(reference.definingClass)).getValue(reference.definingClass)
                val zoom = zoomClass.methods.single { MethodUtil.methodSignaturesMatch(it, reference) }
                assertTrue("$name: the zoom is static", AccessFlags.STATIC.isSet(zoom.accessFlags))
                val zoomCode = zoom.code()
                val length = zoom.implementation!!.registerCount - 1
                // The Long is read for the animator's length, or Facebook's own when it's null.
                val read = zoomCode.indexOfFirst { it.opcode == Opcode.IF_EQZ && (it as OneRegisterInstruction).registerA == length }
                assertTrue("$name: ${reference.name} never tests its length", read >= 0)
                assertTrue("$name: ${reference.name} hands no animator a length", zoomCode.any { it.call?.key() == "Landroid/animation/ValueAnimator;->setDuration(J)Landroid/animation/ValueAnimator;" })

                val moves = photoPageMoves(page, reference)
                val zooms = moves.filter { move -> move.code().any { it.call?.key() == reference.key() } }
                val slides = moves.filter { move -> move.code().any { it.call?.key() == SLIDE } }
                assertTrue("$name: the page zooms as it opens and as it closes", zooms.size >= 2)
                assertTrue("$name: the page slides", slides.isNotEmpty())
                val calls = moves.associateWith { lengthCalls(it) }
                val handed = calls.flatMap { (move, at) -> at.map { move.code()[it].call!! } }
                assertTrue("$name: no slide is handed a length", handed.any { it.name == "setDuration" && it.definingClass.startsWith("Landroid/animation/") })
                assertTrue("$name: the background's fade is handed no length", handed.any { it.returnType == "V" && it.definingClass.startsWith("LX/") })
                // Every long the patch sends through the extension is a length, so a new kind of call fails here first.
                for (call in handed) {
                    assertTrue("$name: ${call.key()} takes a long but is neither an animator's length nor the fade's",
                        (call.name == "setDuration" && call.definingClass.startsWith("Landroid/animation/")) ||
                            (call.returnType == "V" && call.definingClass.startsWith("LX/")))
                }
                println("$name: zoom ${reference.key()}, length in v$length; " +
                    calls.entries.joinToString { (move, at) -> "${move.name} " + at.joinToString("/") { "${move.code()[it].call!!.name}@$it" } })

                val context = PatchContexts.of(listOf(page, zoomClass))
                with(context) { quietPhotoPage() }

                val patchedZoom = context.mutableClassDefBy(zoomClass.type).methods.single { MethodUtil.methodSignaturesMatch(it, reference) }.code()
                assertEquals("$name: two instructions at the zoom's start", zoomCode.size + 2, patchedZoom.size)
                assertEquals("$name: the zoom's call", PHOTO_PAGE_ZOOM, patchedZoom[0].call!!.key())
                assertEquals("$name: handed the length alone", 1, (patchedZoom[0] as FiveRegisterInstruction).registerCount)
                assertEquals("$name: handed the length", length, (patchedZoom[0] as FiveRegisterInstruction).registerC)
                assertEquals("$name: the answer", Opcode.MOVE_RESULT_OBJECT, patchedZoom[1].opcode)
                assertEquals("$name: kept as the length", length, (patchedZoom[1] as OneRegisterInstruction).registerA)
                for (index in zoomCode.indices) assertEquals("$name: Facebook's own at $index", zoomCode[index].opcode, patchedZoom[index + 2].opcode)

                val patchedPage = context.mutableClassDefBy(PHOTO_PAGE)
                for ((move, at) in calls) {
                    val before = move.code()
                    val after = patchedPage.methods.single { MethodUtil.methodSignaturesMatch(it, move) }.code()
                    assertEquals("$name: ${move.name} gains two instructions for each length", before.size + 2 * at.size, after.size)
                    var shift = 0
                    for ((index, instruction) in before.withIndex()) {
                        if (index in at) {
                            val pair = instruction as FiveRegisterInstruction
                            val ask = after[index + shift]
                            assertEquals("$name: ${move.name} asks at $index", PHOTO_PAGE_LENGTH, ask.call!!.key())
                            ask as FiveRegisterInstruction
                            assertEquals("$name: ${move.name} hands one pair at $index", 2, ask.registerCount)
                            assertEquals("$name: ${move.name} hands its pair at $index", listOf(pair.registerD, pair.registerE), listOf(ask.registerC, ask.registerD))
                            val answer = after[index + shift + 1]
                            assertEquals("$name: ${move.name} takes the answer at $index", Opcode.MOVE_RESULT_WIDE, answer.opcode)
                            assertEquals("$name: ${move.name} keeps it in the pair at $index", pair.registerD, (answer as OneRegisterInstruction).registerA)
                            shift += 2
                        }
                        assertEquals("$name: Facebook's own in ${move.name} at $index", instruction.opcode, after[index + shift].opcode)
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    @Test
    fun `a page that moves its photo no way refuses, naming the class`() {
        val page = ImmutableClassDef(PHOTO_PAGE, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, null)
        val failure = assertThrows(PatchException::class.java) { photoPageZoom(page) }
        assertEquals("$PATCH: $PHOTO_PAGE moves its photo with 0 methods, expected one", failure.message)
    }

    // A page of one method, A0E (v0 to v8, then this in v9): the zoom with the Long in v5, then a fade
    // handed the pair v6, v7. The zoom's own class does nothing but return.
    private val zoomCall = ImmutableMethodReference("LX/z;", "A00",
        listOf("Landroid/animation/TimeInterpolator;", "LX/l;", "LX/a;", "LX/p;", "LX/p;", "Ljava/lang/Long;"), "V")
    private val fadeCall = ImmutableMethodReference("LX/f;", "A06", listOf("J"), "V")
    private val zooms = ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 0, 6, zoomCall)
    private val aLength = ImmutableInstruction21s(Opcode.CONST_WIDE_16, 6, 250)
    private val fades = ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 8, 6, 7, 0, 0, fadeCall)
    private val returns = ImmutableInstruction10x(Opcode.RETURN_VOID)

    private fun pageAndZoom(code: List<Instruction>) = PatchContexts.of(listOf(
        ImmutableClassDef(PHOTO_PAGE, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(ImmutableMethod(PHOTO_PAGE, "A0E", null, "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(10, code, null, null)))),
        ImmutableClassDef("LX/z;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(ImmutableMethod("LX/z;", "A00", zoomCall.parameterTypes.map { ImmutableMethodParameter(it.toString(), null, null) }, "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(6, listOf(returns), null, null)))),
    ))

    private fun BytecodePatchContext.codeOf(type: String) = mutableClassDefBy(type).methods.single().code()

    @Test
    fun `a page made by hand gets its zoom's Long and its fade's long through the extension`() {
        val context = pageAndZoom(listOf(zooms, aLength, fades, returns))
        with(context) { quietPhotoPage() }

        val zoom = context.codeOf("LX/z;")
        assertEquals(listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_VOID), zoom.map { it.opcode })
        assertEquals(PHOTO_PAGE_ZOOM, zoom[0].call!!.key())
        assertEquals(listOf(1, 5), (zoom[0] as FiveRegisterInstruction).let { listOf(it.registerCount, it.registerC) })
        assertEquals(5, (zoom[1] as OneRegisterInstruction).registerA)

        val page = context.codeOf(PHOTO_PAGE)
        assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.CONST_WIDE_16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_WIDE, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID),
            page.map { it.opcode })
        assertEquals(PHOTO_PAGE_LENGTH, page[2].call!!.key())
        assertEquals(listOf(2, 6, 7), (page[2] as FiveRegisterInstruction).let { listOf(it.registerCount, it.registerC, it.registerD) })
        assertEquals(6, (page[3] as OneRegisterInstruction).registerA)
    }

    @Test
    fun `a page that reads a double field refuses and leaves the zoom as it was`() {
        val readsADouble = ImmutableInstruction22c(Opcode.IGET_WIDE, 6, 9, ImmutableFieldReference(PHOTO_PAGE, "A00", "D"))
        val context = pageAndZoom(listOf(zooms, readsADouble, fades, returns))
        val failure = assertThrows(PatchException::class.java) { with(context) { quietPhotoPage() } }
        assertEquals("$PATCH: $PHOTO_PAGE->A0E works with doubles", failure.message)
        assertEquals(listOf(Opcode.RETURN_VOID), context.codeOf("LX/z;").map { it.opcode })
    }

    @Test
    fun `a page that jumps straight to a length refuses and leaves the zoom as it was`() {
        // if-eqz (2 code units) over the const-wide/16 (2) lands on the fade's call.
        val jumps = ImmutableInstruction21t(Opcode.IF_EQZ, 8, 4)
        val context = pageAndZoom(listOf(zooms, jumps, aLength, fades, returns))
        val failure = assertThrows(PatchException::class.java) { with(context) { quietPhotoPage() } }
        assertEquals("$PATCH: $PHOTO_PAGE->A0E jumps straight to a length at 3", failure.message)
        assertEquals(listOf(Opcode.RETURN_VOID), context.codeOf("LX/z;").map { it.opcode })
    }
}
