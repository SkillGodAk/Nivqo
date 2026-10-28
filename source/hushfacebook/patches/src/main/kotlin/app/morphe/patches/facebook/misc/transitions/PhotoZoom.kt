/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.transitions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil

/*
 * The zoom a photo opens and closes with. Facebook's photo viewer (SutroPhotoAnimationDialogFragment,
 * a kept name) grows a feed photo from its place to full screen, and shrinks it back on Back. On a
 * phone Facebook rates 2015 or newer, both go through one method that sets a Rebound spring going
 * (583 `LX/AeD;->A01(Drawable, odx, Adc from, Adc to)`, the spring `LX/2vR;`), and it ends:
 *
 *     iget-object v2, p0, LX/AeD;->A03:LX/2vR;
 *     if-eqz v2, :done
 *     const-wide/high16 v0, 0x3ff0000000000000L      # 1.0
 *     invoke-virtual {v2, v0, v1}, LX/2vR;->A02(D)V   # setEndValue
 *     :done
 *     return-void
 *
 * Each frame the spring moves, its listener draws the photo between the two places, and once the
 * spring rests the listener tells the viewer the zoom is done. With the switch on, the patch moves
 * the spring to its end value at once (setCurrentValue, not at rest) and gives it a speed just over
 * the one it rests below. Rebound's loop passes over a spring that rests and rested the frame before
 * without calling its listener, so a spring put straight at rest would leave the viewer waiting for
 * a zoom that never ends. With the nudge, the next frames call the listener as always, with the
 * photo already in place, and the spring rests a few frames later. (A rest speed of 0 would leave
 * the nudge at 0 too, but then Facebook's own zoom would never rest either; Rebound's is 0.005.)
 *
 * A phone rated older than 2015 takes a ValueAnimator instead (583 `LX/AeB;->A00`), the one a
 * multi-photo post's photo page zooms with, and PhotoPage.kt has its length go through the
 * extension. A viewer whose photo can't go back to its place slides away over 250 ms instead (583
 * `A0C`), and one opened without its thumbnail slides up from the bottom (`A0G`), each with a fade
 * of its background, and their lengths go through the extension the way the photo page's do.
 */

internal const val PHOTO_VIEWER = "Lcom/facebook/photos/dialog/SutroPhotoAnimationDialogFragment;"
internal const val PHOTO_ZOOMS = "$SCREEN_TRANSITIONS->photoZooms()Z"
internal const val PHOTO_VIEWER_LENGTH = "$SCREEN_TRANSITIONS->photoViewerLength(J)J"

/** The year class the photo viewer compares the phone's with, 2015, before it picks the spring. */
internal const val SPRING_YEAR = 0x7df

private const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"

private val Instruction.called: MethodReference?
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

private val Instruction.field: FieldReference?
    get() = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun MethodReference.shape() = parameterTypes.map(CharSequence::toString)

private fun MethodReference.smali() = "$definingClass->$name${parameterTypes.joinToString("", "(", ")")}$returnType"

private fun FieldReference.smali() = "$definingClass->$name:$type"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

/** A call that sets the zoom going: (Drawable, listener, from, to)V, the two places of one type. */
private fun MethodReference.startsAZoom(): Boolean {
    val shape = shape()
    return returnType == "V" && shape.size == 4 && shape[0] == DRAWABLE && shape[2] == shape[3] && shape[2].startsWith("L")
}

/** The method [open] calls to set the zoom going (583 `LX/AeD;->A01`). */
internal fun photoZoomStart(open: Method): MethodReference {
    val starts = open.code().filter { it.opcode == Opcode.INVOKE_VIRTUAL }.mapNotNull { it.called }
        .filter { it.startsAZoom() }.distinctBy { it.smali() }
    return starts.singleOrNull()
        ?: throw PatchException("$PATCH: ${open.definingClass}->${open.name} sets ${starts.size} zooms going, expected one")
}

/** What the patch needs of the spring: where the zoom keeps it, and what it calls on it. */
internal class PhotoSpring(
    val field: FieldReference,
    val endValue: FieldReference,
    val setCurrentValue: MethodReference,
    val setVelocity: MethodReference,
    val restSpeed: FieldReference,
)

private fun ClassDef.instanceMethods(returnType: String, shape: List<String>) =
    methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == returnType && it.shape() == shape }

/**
 * The spring [start] sets going, read from [start] and the spring's class [spring]. [setEndValue]
 * is the call [start] ends with.
 */
internal fun photoSpring(start: Method, setEndValue: MethodReference, spring: ClassDef): PhotoSpring {
    val where = "${start.definingClass}->${start.name}"
    val kept = start.code().filter { it.opcode == Opcode.IPUT_OBJECT }.mapNotNull { it.field }
        .filter { it.definingClass == start.definingClass && it.type == spring.type }.distinctBy { it.smali() }
    val field = kept.singleOrNull() ?: throw PatchException("$PATCH: $where keeps ${kept.size} springs, expected one")

    // setEndValue keeps its argument, the wide pair after `this` in its last three registers, in a field of the spring.
    val ends = spring.methods.filter { MethodUtil.methodSignaturesMatch(it, setEndValue) }
    val end = ends.singleOrNull() ?: throw PatchException("$PATCH: ${spring.type} has ${ends.size} ${setEndValue.name}(D)V")
    val argument = (end.implementation ?: throw PatchException("$PATCH: ${spring.type}->${end.name} has no code")).registerCount - 2
    val endValues = end.code().filter { it.opcode == Opcode.IPUT_WIDE && (it as OneRegisterInstruction).registerA == argument }
        .mapNotNull { it.field }.filter { it.definingClass == spring.type }.distinctBy { it.smali() }
    val endValue = endValues.singleOrNull()
        ?: throw PatchException("$PATCH: ${spring.type}->${end.name} keeps its value in ${endValues.size} fields, expected one")

    val currents = spring.instanceMethods("V", listOf("D", "Z"))
    val setCurrentValue = currents.singleOrNull()
        ?: throw PatchException("$PATCH: ${spring.type} has ${currents.size} (DZ)V methods, expected setCurrentValue alone")
    val velocities = spring.instanceMethods("V", listOf("D")).filter { it.name != setEndValue.name }
    val setVelocity = velocities.singleOrNull()
        ?: throw PatchException("$PATCH: ${spring.type} has ${velocities.size} (D)V methods besides ${setEndValue.name}, expected setVelocity alone")
    // isAtRest compares the speed with the spring's rest speed before anything else of the spring's own.
    val atRests = spring.instanceMethods("Z", emptyList())
    val atRest = atRests.singleOrNull() ?: throw PatchException("$PATCH: ${spring.type} has ${atRests.size} ()Z methods, expected isAtRest alone")
    val restSpeed = atRest.code().filter { it.opcode == Opcode.IGET_WIDE }.mapNotNull { it.field }
        .firstOrNull { it.definingClass == spring.type }
        ?: throw PatchException("$PATCH: ${spring.type}->${atRest.name} reads no speed of the spring's own")
    if (restSpeed == endValue) throw PatchException("$PATCH: ${spring.type}->${atRest.name} reads the end value first")
    return PhotoSpring(field, endValue, setCurrentValue, setVelocity, restSpeed)
}

/**
 * Has the photo viewer's zoom ask the extension, as it sets its spring going, whether it plays.
 * While the extension says no, the spring is put at its end value with a nudge over its rest speed.
 */
internal fun BytecodePatchContext.quietPhotoZoom(open: Method) {
    val reference = photoZoomStart(open)
    val start = mutableClassDefByOrNull(reference.definingClass)?.methods?.singleOrNull { MethodUtil.methodSignaturesMatch(it, reference) }
        ?: throw PatchException("$PATCH: ${reference.definingClass} doesn't declare ${reference.name}")
    val where = "${start.definingClass}->${start.name}"
    val implementation = start.implementation ?: throw PatchException("$PATCH: $where has no code")
    val code = implementation.instructions.toList()
    // The spring's end value is the last thing set before the method returns.
    val at = code.indexOfLast { it.opcode == Opcode.INVOKE_VIRTUAL && it.called?.let { call -> call.returnType == "V" && call.shape() == listOf("D") } == true }
    if (at < 0) throw PatchException("$PATCH: $where sets no end value")
    if (code.getOrNull(at + 1)?.opcode != Opcode.RETURN_VOID) throw PatchException("$PATCH: $where doesn't return right after setting the end value")
    val setEndValue = code[at].called!!
    val spring = photoSpring(start, setEndValue, classDefByOrNull(setEndValue.definingClass)
        ?: throw PatchException("$PATCH: no ${setEndValue.definingClass} for the spring"))

    // Right before the return no local is read again; the answer, the spring and the flag take four.
    val types = start.parameterTypes.map(CharSequence::toString)
    val parameters = 1 + types.size + types.count { it == "J" || it == "D" }
    val locals = implementation.registerCount - parameters
    if (locals < 4) throw PatchException("$PATCH: $where has $locals locals, expected four or more")
    // p0 is v<locals>, and iget-object names its object in four bits.
    if (locals > 15) throw PatchException("$PATCH: $where keeps the zoom in v$locals, past v15")
    start.addInstructionsWithLabels(
        at + 1,
        """
            invoke-static { }, $PHOTO_ZOOMS
            move-result v0
            if-nez v0, :zooms
            iget-object v2, p0, ${spring.field.smali()}
            if-eqz v2, :zooms
            iget-wide v0, v2, ${spring.endValue.smali()}
            const/4 v3, 0x0
            invoke-virtual { v2, v0, v1, v3 }, ${spring.setCurrentValue.smali()}
            iget-wide v0, v2, ${spring.restSpeed.smali()}
            add-double/2addr v0, v0
            invoke-virtual { v2, v0, v1 }, ${spring.setVelocity.smali()}
        """,
        ExternalLabel("zooms", start.getInstruction(at + 1)),
    )
}

/** The photo viewer's methods that slide it, away on Back and up for a photo opened without its place (583 `A0C` and `A0G`). */
internal fun photoViewerSlides(viewer: ClassDef): List<Method> {
    val slides = viewer.methods.filter { method -> method.code().any { it.called?.makesASlide() == true } }
    if (slides.isEmpty()) throw PatchException("$PATCH: no method of ${viewer.type} slides it")
    return slides
}

/**
 * Has the photo viewer's slides, and the fade of its background that goes with each, ask the
 * extension for their length, as the photo page's do (see PhotoPage.kt).
 */
internal fun BytecodePatchContext.quietPhotoViewerSlides() {
    val viewer = mutableClassDefByOrNull(PHOTO_VIEWER) ?: throw PatchException("$PATCH: no $PHOTO_VIEWER")
    wrapLengths(checkedLengths(photoViewerSlides(viewer)), PHOTO_VIEWER_LENGTH)
}
