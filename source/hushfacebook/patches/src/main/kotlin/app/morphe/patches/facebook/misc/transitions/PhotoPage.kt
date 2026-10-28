/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.transitions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.util.MethodUtil

/*
 * The page a multi-photo post's photo opens on. A tap on one of the post's tiles ("Photo 1 of 5,
 * expand photo") shows its photos in PhotoAnimationDialogFragment (a kept name, tag
 * PhotoAnimationDialogFragment_PHOTOS_FEED), not in the Sutro viewer PhotoZoom.kt quiets, and the
 * page moves with animators that run for a length:
 *
 * - The photo grows from its tile to its place on the page, and shrinks back on Back, through one
 *   static method (583 `LX/AeB;->A00(TimeInterpolator, listener, animator, Adc from, Adc to, Long)`)
 *   that runs a ValueAnimator for the Long's length, 300 ms when it's null, which the page passes.
 *   The Sutro viewer calls the same method on a phone Facebook rates older than 2015.
 * - The page's gray background fades in over 100 ms and out again (583 `LX/AeL;->A06(J)V`, the
 *   length of the background view's ViewPropertyAnimator).
 * - A page whose photo can't go back to its tile slides away over 250 ms, and one opened without
 *   its tile slides up, each an ObjectAnimator.ofFloat on translationX or translationY.
 *
 * With the switch on, each runs with no length. The zoom's Long goes through the extension as the
 * method starts. In the page's methods that set the zoom or a slide going, each call handed a
 * length, and nothing else, gets it through the extension first, back in the same registers. An
 * animator of no length draws its end at once and tells its listeners it ended on the next frame,
 * so the page goes on as it does after its own animation, as with Android's animator duration
 * scale at 0.
 */

internal const val PHOTO_PAGE = "Lcom/facebook/photos/dialog/PhotoAnimationDialogFragment;"
internal const val PHOTO_PAGE_ZOOM = "$SCREEN_TRANSITIONS->photoPageZoom(Ljava/lang/Long;)Ljava/lang/Long;"
internal const val PHOTO_PAGE_LENGTH = "$SCREEN_TRANSITIONS->photoPageLength(J)J"
internal const val SLIDE = "Landroid/animation/ObjectAnimator;->ofFloat(Ljava/lang/Object;Ljava/lang/String;[F)Landroid/animation/ObjectAnimator;"

private const val INTERPOLATOR = "Landroid/animation/TimeInterpolator;"
private const val LENGTH = "Ljava/lang/Long;"
private const val OBJECT_ANIMATOR = "Landroid/animation/ObjectAnimator;"
private const val PROPERTY = "Landroid/util/Property;"

private val Instruction.called: MethodReference?
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MethodReference.shape() = parameterTypes.map(CharSequence::toString)

private fun MethodReference.smali() = "$definingClass->$name${parameterTypes.joinToString("", "(", ")")}$returnType"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

/** A call that moves a photo between two places: static (TimeInterpolator, listener, animator, from, to, Long)V. */
private fun MethodReference.movesAPhoto(): Boolean {
    val shape = shape()
    return returnType == "V" && shape.size == 6 && shape[0] == INTERPOLATOR && shape[3] == shape[4] && shape[3].startsWith("L") &&
        shape[5] == LENGTH
}

/**
 * A call that makes the animator a view slides with: an ObjectAnimator of a property named by its
 * name ([SLIDE]) or by a Property, as the photo viewer's own maker takes it (583 `LX/ldC;->A0V`).
 */
internal fun MethodReference.makesASlide(): Boolean = smali() == SLIDE || (returnType == OBJECT_ANIMATOR && PROPERTY in shape())

/**
 * Whether [method] may read or make a double anywhere: the opcode mnemonics name the double ops
 * ("add-double", "int-to-double"), a call or field names its type, and a wide array element could be
 * one, since aget-wide and aput-wide don't say which.
 */
private fun usesDoubles(method: Method): Boolean = method.returnType == "D" || method.code().any { instruction ->
    "double" in instruction.opcode.name || // opcode-name-ok: the mnemonic is what names the double ops
        instruction.opcode == Opcode.AGET_WIDE || instruction.opcode == Opcode.APUT_WIDE ||
        instruction.called?.let { it.returnType == "D" || "D" in it.shape() } == true ||
        ((instruction as? ReferenceInstruction)?.reference as? FieldReference)?.type == "D"
}

/** The method the photo page grows and shrinks its photo with (583 `LX/AeB;->A00`). */
internal fun photoPageZoom(page: ClassDef): MethodReference {
    val zooms = page.methods.flatMap { it.code() }
        .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .mapNotNull { it.called }.filter { it.movesAPhoto() }.distinctBy { it.smali() }
    return zooms.singleOrNull() ?: throw PatchException("$PATCH: ${page.type} moves its photo with ${zooms.size} methods, expected one")
}

/** The photo page's methods that set its zoom or a slide going (583 `A0B`, `A0C` and `A0E`). */
internal fun photoPageMoves(page: ClassDef, zoom: MethodReference): List<Method> {
    val moves = page.methods.filter { method ->
        method.code().any { it.called?.let { call -> call.smali() == zoom.smali() || call.makesASlide() } == true }
    }
    if (moves.isEmpty()) throw PatchException("$PATCH: no method of ${page.type} sets its zoom or a slide going")
    return moves
}

/** Where [method] hands something a length: an invoke-virtual of one long and nothing else. */
internal fun lengthCalls(method: Method): List<Int> = method.code().withIndex()
    .filter { (_, it) -> it.opcode == Opcode.INVOKE_VIRTUAL && it.called?.shape() == listOf("J") }.map { it.index }

/**
 * Has the photo page's zoom, its background's fade and its slides ask the extension for their
 * length. Every method is checked before any is changed, so a build that moves the page another
 * way is left as it was.
 */
internal fun BytecodePatchContext.quietPhotoPage() {
    val page = mutableClassDefByOrNull(PHOTO_PAGE) ?: throw PatchException("$PATCH: no $PHOTO_PAGE")
    val reference = photoPageZoom(page)
    val zoom = mutableClassDefByOrNull(reference.definingClass)?.methods?.singleOrNull { MethodUtil.methodSignaturesMatch(it, reference) }
        ?: throw PatchException("$PATCH: ${reference.definingClass} doesn't declare ${reference.name}")
    val where = "${zoom.definingClass}->${zoom.name}"
    if (!AccessFlags.STATIC.isSet(zoom.accessFlags)) throw PatchException("$PATCH: $where isn't static")
    // Six single-register parameters, the Long last: it's the method's last register.
    val length = (zoom.implementation ?: throw PatchException("$PATCH: $where has no code")).registerCount - 1
    if (length > 15) throw PatchException("$PATCH: $where keeps its length in v$length, past v15")

    val lengths = checkedLengths(photoPageMoves(page, reference))

    zoom.addInstructions(
        0,
        """
            invoke-static { v$length }, $PHOTO_PAGE_ZOOM
            move-result-object v$length
        """,
    )
    wrapLengths(lengths, PHOTO_PAGE_LENGTH)
}

/**
 * Where each of [moves] hands a length, checked before anything is changed. The answer goes back
 * in the long's own registers, typed long from then on, so a method that works with doubles, a
 * length a branch jumps straight to, or one handed in anything but one register pair refuses.
 */
internal fun checkedLengths(moves: List<Method>): Map<MutableMethod, List<Int>> = moves.map { it as MutableMethod }.associateWith { move ->
    val name = "${move.definingClass}->${move.name}"
    if (usesDoubles(move)) throw PatchException("$PATCH: $name works with doubles")
    lengthCalls(move).onEach { at ->
        val call = move.implementation!!.instructions.elementAt(at)
        if ((call as BuilderInstruction).location.labels.isNotEmpty()) throw PatchException("$PATCH: $name jumps straight to a length at $at")
        val registers = call as FiveRegisterInstruction
        if (registers.registerCount != 3 || registers.registerE != registers.registerD + 1) {
            throw PatchException("$PATCH: $name hands a length at $at in registers other than one pair")
        }
    }
}

/** Has each length in [lengths] go through [hook] right before the call it's handed to, back in the same pair. */
internal fun wrapLengths(lengths: Map<MutableMethod, List<Int>>, hook: String) {
    for ((move, calls) in lengths) {
        for (at in calls.asReversed()) {
            val call = move.implementation!!.instructions.elementAt(at) as FiveRegisterInstruction
            move.addInstructions(
                at,
                """
                    invoke-static { v${call.registerD}, v${call.registerE} }, $hook
                    move-result-wide v${call.registerD}
                """,
            )
        }
    }
}
