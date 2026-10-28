/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.transitions

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.util.MethodUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Turn off screen transitions' photo zoom on every Facebook build the bundle declares. The photo
 * viewer's open sets the zoom going through one method, which makes a spring, sets its end value
 * and returns; the spring can be put at a value without resting, and its speed set. Then the patch:
 * before that return, while the extension says no, the spring sits at its end value with a nudge
 * over its rest speed. The viewer's slides, and the fade of its background with each, hand their
 * length as a long, and the patch has each go through the extension right before the call, back in
 * the same pair. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class PhotoZoomFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private val Instruction.field: FieldReference?
        get() = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun MethodReference.shape() = parameterTypes.map(CharSequence::toString)

    private fun MethodReference.key() = definingClass + "->" + name + parameterTypes.joinToString("", "(", ")") + returnType

    @Before
    @After
    fun forgetTheLastMatch() {
        PhotoOpenFingerprint.clearMatch()
    }

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    @Test
    fun `each declared build zooms a photo on one spring, and the patch puts it at its end before the return`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name

                val viewer = FixtureDex.classes(bundle, setOf(PHOTO_VIEWER))[PHOTO_VIEWER]
                    ?: throw AssertionError("$name: no $PHOTO_VIEWER")
                val opens = viewer.methods.filter { method ->
                    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.shape() == listOf(PHOTO_VIEWER, "Z") &&
                        method.code().any { it.opcode == Opcode.CONST_16 && (it as NarrowLiteralInstruction).narrowLiteral == SPRING_YEAR }
                }
                assertEquals("$name: static (viewer, Z)V methods comparing with 2015", 1, opens.size)
                val open = opens.single()
                val reference = photoZoomStart(open)

                val zoom = FixtureDex.classes(bundle, setOf(reference.definingClass)).getValue(reference.definingClass)
                val start = zoom.methods.single { MethodUtil.methodSignaturesMatch(it, reference) }
                assertTrue("$name: the zoom starts from an instance method", !AccessFlags.STATIC.isSet(start.accessFlags))
                val startCode = start.code()
                val at = startCode.indexOfLast { it.opcode == Opcode.INVOKE_VIRTUAL && it.call?.let { c -> c.returnType == "V" && c.shape() == listOf("D") } == true }
                assertTrue("$name: ${start.name} sets no end value", at > 0)
                assertEquals("$name: ${start.name} returns right after setting the end value", Opcode.RETURN_VOID, startCode[at + 1].opcode)
                val setEndValue = startCode[at].call!!
                val springType = setEndValue.definingClass
                assertTrue("$name: ${start.name} makes no spring", startCode.any { it.call?.returnType == springType })

                val spring = FixtureDex.classes(bundle, setOf(springType)).getValue(springType)
                val found = photoSpring(start, setEndValue, spring)
                assertEquals("$name: the spring's field", springType, found.field.type)
                // setCurrentValue tells every listener, then rests the spring only when its flag says so.
                val current = spring.methods.single { MethodUtil.methodSignaturesMatch(it, found.setCurrentValue) }.code()
                assertTrue("$name: setCurrentValue tells no listener", current.any { it.opcode == Opcode.INVOKE_INTERFACE && it.call?.shape() == listOf(springType) })
                val rests = current.indexOfFirst { it.opcode == Opcode.INVOKE_VIRTUAL && it.call?.definingClass == springType && it.call?.shape()?.isEmpty() == true }
                assertTrue("$name: setCurrentValue never rests the spring", rests > 0)
                assertEquals("$name: setCurrentValue rests the spring only on its flag", Opcode.IF_EQZ, current[rests - 1].opcode)
                // setVelocity wakes the spring system as setEndValue does, so the loop runs again.
                val wakes = { method: MethodReference ->
                    spring.methods.single { MethodUtil.methodSignaturesMatch(it, method) }.code().filter { it.opcode == Opcode.INVOKE_VIRTUAL }
                        .mapNotNull { it.call }.filter { it.shape() == listOf("Ljava/lang/String;") && it.returnType == "V" }.map { it.key() }.toSet()
                }
                val woken = wakes(setEndValue)
                assertEquals("$name: setEndValue wakes one spring system", 1, woken.size)
                assertEquals("$name: setVelocity wakes it the same way", woken, wakes(found.setVelocity))
                println("$name: ${open.definingClass}->${open.name} starts ${reference.key()}; spring $springType in ${found.field.name}, " +
                    "end ${found.endValue.name}, setCurrentValue ${found.setCurrentValue.name}, setVelocity ${found.setVelocity.name}, rest speed ${found.restSpeed.name}")

                val context = PatchContexts.of(listOf(viewer, zoom, spring))
                forgetTheLastMatch()
                with(context) { quietPhotoZoom(PhotoOpenFingerprint.method) }

                val patched = context.mutableClassDefBy(zoom.type).methods.single { MethodUtil.methodSignaturesMatch(it, reference) }
                val code = patched.implementation!!.instructions.toList()
                assertEquals("$name: eleven instructions before the return", startCode.size + 11, code.size)
                for (index in 0..at) assertEquals("$name: Facebook's own at $index", startCode[index].opcode, code[index].opcode)
                val ask = code[at + 1]
                assertEquals("$name: the call", PHOTO_ZOOMS, ask.call!!.key())
                assertEquals("$name: the spring", found.field.name, code[at + 4].field!!.name)
                assertEquals("$name: the end value", found.endValue.name, code[at + 6].field!!.name)
                val place = code[at + 8] as FiveRegisterInstruction
                assertEquals("$name: put at the end value", found.setCurrentValue.key(), code[at + 8].call!!.key())
                assertEquals("$name: the spring, the value and not at rest", listOf(2, 0, 1, 3), listOf(place.registerC, place.registerD, place.registerE, place.registerF))
                assertEquals("$name: the rest speed", found.restSpeed.name, code[at + 9].field!!.name)
                assertEquals("$name: doubled", Opcode.ADD_DOUBLE_2ADDR, code[at + 10].opcode)
                assertEquals("$name: as the spring's speed", found.setVelocity.key(), code[at + 11].call!!.key())
                assertEquals("$name: then Facebook's return", Opcode.RETURN_VOID, code[at + 12].opcode)
                for (branch in listOf(at + 3, at + 5)) {
                    val target = (patched.implementation!!.instructions.elementAt(branch) as BuilderOffsetInstruction).target.location.index
                    assertEquals("$name: the way past the nudge at $branch", at + 12, target)
                }
                val tested = code[at + 5] as OneRegisterInstruction
                assertEquals("$name: a spring that's gone is left alone", 2, tested.registerA)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    @Test
    fun `each declared build slides its photo viewer for a length, and the patch has each length go through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val viewer = FixtureDex.classes(bundle, setOf(PHOTO_VIEWER))[PHOTO_VIEWER]
                    ?: throw AssertionError("$name: no $PHOTO_VIEWER")
                val slides = photoViewerSlides(viewer)
                assertTrue("$name: the viewer slides away on Back and up as it opens without its place", slides.size >= 2)
                val calls = slides.associateWith { lengthCalls(it) }
                val handed = calls.flatMap { (slide, at) -> at.map { slide.code()[it].call!! } }
                assertTrue("$name: no slide is handed a length", handed.any { it.name == "setDuration" && it.definingClass.startsWith("Landroid/animation/") })
                assertTrue("$name: the background's fade is handed no length", handed.any { it.returnType == "V" && it.definingClass.startsWith("LX/") })
                // Every long the patch sends through the extension is a length, so a new kind of call fails here first.
                for (call in handed) {
                    assertTrue("$name: ${call.key()} takes a long but is neither an animator's length nor the fade's",
                        (call.name == "setDuration" && call.definingClass.startsWith("Landroid/animation/")) ||
                            (call.returnType == "V" && call.definingClass.startsWith("LX/")))
                }
                println("$name: " + calls.entries.joinToString { (slide, at) -> "${slide.name} " + at.joinToString("/") { "${slide.code()[it].call!!.name}@$it" } })

                val context = PatchContexts.of(listOf(viewer))
                with(context) { quietPhotoViewerSlides() }
                val patched = context.mutableClassDefBy(PHOTO_VIEWER)
                for ((slide, at) in calls) {
                    val before = slide.code()
                    val after = patched.methods.single { MethodUtil.methodSignaturesMatch(it, slide) }.code()
                    assertEquals("$name: ${slide.name} gains two instructions for each length", before.size + 2 * at.size, after.size)
                    var shift = 0
                    for ((index, instruction) in before.withIndex()) {
                        if (index in at) {
                            val pair = instruction as FiveRegisterInstruction
                            assertEquals("$name: ${slide.name} asks at $index", PHOTO_VIEWER_LENGTH, after[index + shift].call!!.key())
                            val ask = after[index + shift] as FiveRegisterInstruction
                            assertEquals("$name: ${slide.name} hands one pair at $index", 2, ask.registerCount)
                            assertEquals("$name: ${slide.name} hands its pair at $index", listOf(pair.registerD, pair.registerE), listOf(ask.registerC, ask.registerD))
                            val answer = after[index + shift + 1]
                            assertEquals("$name: ${slide.name} takes the answer at $index", Opcode.MOVE_RESULT_WIDE, answer.opcode)
                            assertEquals("$name: ${slide.name} keeps it in the pair at $index", pair.registerD, (answer as OneRegisterInstruction).registerA)
                            shift += 2
                        }
                        assertEquals("$name: ${slide.name}'s own at $index", instruction.opcode, after[index + shift].opcode)
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    @Test
    fun `a viewer that slides no way refuses, naming the class`() {
        val viewer = ImmutableClassDef(PHOTO_VIEWER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, null)
        val failure = assertThrows(PatchException::class.java) { photoViewerSlides(viewer) }
        assertEquals("$PATCH: no method of $PHOTO_VIEWER slides it", failure.message)
    }

    @Test
    fun `a viewer whose open sets no zoom going refuses, naming the method`() {
        val method = ImmutableMethod("LX/x;", "A0G", null, "V", AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null))
        val failure = assertThrows(PatchException::class.java) { photoZoomStart(method) }
        assertEquals("$PATCH: LX/x;->A0G sets 0 zooms going, expected one", failure.message)
    }
}
