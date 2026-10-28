/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The ring between the overlapping reaction icons in a post's footer (#147). Read from 582, 2026-10-14;
 * the obfuscated names here are for reviewers, the code never writes one down.
 *
 * The footer's reactions add-on is a Litho component named "FDSInlineReactions" (arm64 LX/3Dz;,
 * armv7 LX/3Et;). Its one static factory for the facepile (arm64 A0F) reads the bling bar style's
 * clipColor (an enum whose fields keep their names: COLOR and MEDIA give ALWAYS_TRANSPARENT, SURFACE
 * gives SURFACE_BACKGROUND), resolves it through FDSColors (renamed by Redex on 584) on the row's context,
 * and hands the answer to the facepile drawable (arm64 LX/3E0;) as its ring colour. That drawable
 * cuts each ring out with clipPath when the colour has any transparency, and paints a disc of it
 * otherwise. A light answer on a black page is the white disc behind the icons.
 */

internal const val INLINE_REACTIONS = "FDSInlineReactions"

/** The bling bar style's field the ring's colour token comes from. */
internal const val CLIP_COLOR = "clipColor"

internal const val REACTION_RING = "Lapp/morphe/extension/facebook/theme/ReactionRing;->ring(I)I"

/**
 * The index of the move-result of each ring colour [method] resolves: a call taking the token and answering an
 * int, after a read of a `clipColor` field and before any other such read.
 */
internal fun reactionRingReads(method: Method): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val reads = mutableListOf<Int>()
    code.forEachIndexed { at, instruction ->
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        if (instruction.opcode != Opcode.IGET_OBJECT || field?.name != CLIP_COLOR) return@forEachIndexed
        val resolve = (at + 1 until code.size).firstOrNull { index ->
            val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
            call != null && code[index].opcode.name.startsWith("invoke")
        } ?: return@forEachIndexed
        val call = (code[resolve] as ReferenceInstruction).reference as MethodReference
        val token = call.parameterTypes.any { it.toString() == field.type }
        if (token && call.returnType == "I" && code.getOrNull(resolve + 1)?.opcode == Opcode.MOVE_RESULT) {
            reads += resolve + 1
        }
    }
    return reads
}

/** Whether [method] already hands the ring's colour to the extension. */
private fun handsRingColour(method: Method): Boolean =
    method.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference)?.toString() == REACTION_RING } == true

/**
 * Hands the ring colour FDSInlineReactions resolves for its facepile to the extension, which in dark
 * mode with AMOLED or Material You in the build gives back a clear one, so the facepile cuts its rings
 * and the page shows through. AMOLED and Material You both call this; the second call finds the hook
 * in place. Refuses unless the component has one method with one such read.
 */
internal fun BytecodePatchContext.hookReactionRing() {
    val components = classDefByStrings(INLINE_REACTIONS, StringComparisonType.EQUALS)
    val component = components.singleOrNull()
        ?: throw PatchException("${components.size} classes are named $INLINE_REACTIONS, expected the footer's reactions")
    val factories = component.methods.filter { reactionRingReads(it).isNotEmpty() }
    val factory = factories.singleOrNull()
        ?: throw PatchException("$INLINE_REACTIONS has ${factories.size} methods resolving a $CLIP_COLOR, expected the facepile's one")
    val parameters = factory.parameterTypes.map(CharSequence::toString)
    val method = mutableClassDefBy(component.type).methods.single {
        it.name == factory.name && it.parameterTypes.map(CharSequence::toString) == parameters
    }
    if (handsRingColour(method)) return
    method.recolourReactionRing()
}

/*
 * The second facepile (#147), read from 582 on 2026-10-14; the obfuscated names are for reviewers.
 * The reactions token pile (arm64 LX/BE5;) is a Drawable built from a Context alone, made by the kept
 * PillsBlingBarView and by the components named UFIFeedbackSummaryComponent and
 * LikesDescriptionComponent and the feedback header's FEEDBACK_HEADER_REACTIONS_TOKEN_PILE. Its
 * constructor resolves a colour token on that context, lets a theme attribute override it, and sets
 * the result on its one Paint. Unless its maker switches it to cutting (A02(true) builds a clipPath
 * circle), draw() paints a disc of that Paint behind every icon.
 */

/** The kept bling bar view whose token pile anchors [hookReactionPile]. */
internal const val PILLS_BLING_BAR = "Lcom/facebook/feedback/reactions/ui/PillsBlingBarView;"

internal const val REACTION_PILE = "Lapp/morphe/extension/facebook/theme/ReactionRing;->pile(I)I"

private const val CONTEXT = "Landroid/content/Context;"
private const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
internal const val PILE_SETS_COLOUR = "Landroid/graphics/Paint;->setColor(I)V"

/**
 * The token pile among the types [bar] makes: each one it creates with `new-instance` and builds
 * with a `(Context)V` constructor, whose class is a Drawable, as [superclassOf] answers.
 */
internal fun reactionPiles(bar: ClassDef, superclassOf: (String) -> String?): List<String> =
    bar.methods.flatMap { method ->
        (method.implementation?.instructions ?: emptyList()).mapNotNull { instruction ->
            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            call?.takeIf {
                instruction.opcode == Opcode.INVOKE_DIRECT && it.name == "<init>" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT)
            }?.definingClass
        }
    }.distinct().filter { superclassOf(it) == DRAWABLE }

/** The index of each `Paint.setColor` call in [method]. */
internal fun pileColourSets(method: Method): List<Int> =
    method.implementation?.instructions?.withIndex()?.filter { (_, it) ->
        it.opcode == Opcode.INVOKE_VIRTUAL && (it as ReferenceInstruction).reference.toString() == PILE_SETS_COLOUR
    }?.map { it.index }.orEmpty()

/**
 * Hands the colour the reactions token pile paints its discs with to the extension, which in dark
 * mode with AMOLED or Material You in the build gives a light one back as the page colour, or clear.
 * AMOLED and Material You both call this; the second call finds the hook in place. Refuses unless
 * PillsBlingBarView makes one such Drawable whose `(Context)` constructor sets one Paint colour.
 */
internal fun BytecodePatchContext.hookReactionPile() {
    val bar = classDefByOrNull(PILLS_BLING_BAR) ?: throw PatchException("this build has no $PILLS_BLING_BAR")
    val piles = reactionPiles(bar) { classDefByOrNull(it)?.superclass }
    val pile = piles.singleOrNull()
        ?: throw PatchException("$PILLS_BLING_BAR makes ${piles.size} Drawables from a Context, expected the reactions token pile")
    val constructor = mutableClassDefBy(pile).methods.single {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT)
    }
    if (constructor.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference)?.toString() == REACTION_PILE } == true) return
    constructor.recolourReactionPile()
}

/**
 * [hookReactionPile] for the constructor itself: the extension right before its one `Paint.setColor`,
 * on the colour's register. Refuses when anything but the instruction before it reaches that call,
 * since a jump onto it would skip the hook.
 */
internal fun MutableMethod.recolourReactionPile() {
    val sets = pileColourSets(this)
    val at = sets.singleOrNull() ?: throw PatchException("$definingClass->$name sets ${sets.size} Paint colours, expected one")
    val flow = ControlFlow.of(this)
    val into = flow.normal.indices.filter { at in flow.normal[it] || at in flow.exceptional[it] }
    if (into != listOf(at - 1)) throw PatchException("$definingClass->$name reaches its Paint colour from $into, expected only ${at - 1}")
    val colour = (getInstruction(at) as FiveRegisterInstruction).registerD
    addInstructions(
        at,
        """
            invoke-static/range { v$colour .. v$colour }, $REACTION_PILE
            move-result v$colour
        """,
    )
}

/** [hookReactionRing] for the factory itself: the extension right after the one read, on its register. */
internal fun MutableMethod.recolourReactionRing() {
    val reads = reactionRingReads(this)
    if (reads.size != 1) {
        throw PatchException("$definingClass->$name resolves ${reads.size} ring colours, expected one")
    }
    val moveResult = reads.single()
    val register = (implementation!!.instructions[moveResult] as OneRegisterInstruction).registerA
    addInstructions(
        moveResult + 1,
        """
            invoke-static/range { v$register .. v$register }, $REACTION_RING
            move-result v$register
        """,
    )
}
