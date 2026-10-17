/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.refresh.enumConstant
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reaction facepile's ring (#147) on every Facebook build the bundle declares. One class is named
 * FDSInlineReactions, and one of its methods resolves the bling bar style's clipColor through FDSColors
 * and hands the answer to a Drawable made with (List, int, int, int, boolean, boolean) as its fourth
 * argument. That drawable reads the colour's alpha and has both ways to separate the icons, a clipPath
 * cut and a painted circle, so a clear colour picks the cut. The style enum's SURFACE entry takes
 * SURFACE_BACKGROUND there, the token that resolved white. The hook puts the extension right after the
 * read on the same register, as a range call, and the other theme's second call leaves it as it is.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class ReactionRingFixtureTest {
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

    private fun ClassDef.calls(owner: String, name: String) = methods.any { method ->
        method.implementation?.instructions?.any { instruction ->
            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            call?.definingClass == owner && call.name == name
        } == true
    }

    @Test
    fun `the facepile's ring colour goes through the extension on every declared build`() = bundles { bundle ->
        val name = bundle.name
        val components = FixtureDex.classesHolding(bundle, INLINE_REACTIONS)
        assertEquals("$name: classes named $INLINE_REACTIONS", 1, components.size)
        val component = components.single()
        val factories = component.methods.filter { reactionRingReads(it).isNotEmpty() }
        assertEquals("$name: methods resolving a $CLIP_COLOR", 1, factories.size)
        val factory = factories.single()
        val original = factory.implementation!!.instructions.toList()
        val read = reactionRingReads(factory).single()
        val register = (original[read] as OneRegisterInstruction).registerA

        // The answer is the facepile drawable's ring colour, its constructor's fourth argument.
        val made = original.drop(read + 1).firstOrNull { it.opcode == Opcode.INVOKE_DIRECT_RANGE || it.opcode == Opcode.INVOKE_DIRECT }
        val constructor = (made as? ReferenceInstruction)?.reference as? MethodReference
        assertEquals("$name: the facepile's constructor", listOf("Ljava/util/List;", "I", "I", "I", "Z", "Z"),
            constructor?.parameterTypes?.map { it.toString() })
        val range = made as RegisterRangeInstruction
        assertEquals("$name: the ring colour is the fourth argument", register, range.startRegister + 4)

        // The drawable cuts the ring for a colour under full alpha, and paints it otherwise.
        val drawable = FixtureDex.classes(bundle, setOf(constructor!!.definingClass)).getValue(constructor.definingClass)
        assertEquals("$name: the facepile is a Drawable", "Landroid/graphics/drawable/Drawable;", drawable.superclass)
        assertTrue("$name: the facepile reads no alpha", drawable.calls("Landroid/graphics/Color;", "alpha"))
        assertTrue("$name: the facepile has no cut", drawable.calls("Landroid/graphics/Canvas;", "clipPath"))
        assertTrue("$name: the facepile paints no ring", drawable.calls("Landroid/graphics/Canvas;", "drawCircle"))

        // The style the colour comes from: SURFACE takes SURFACE_BACKGROUND, the others ALWAYS_TRANSPARENT.
        val clip = original.take(read).last { it.opcode == Opcode.IGET_OBJECT }.let { (it as ReferenceInstruction).reference as FieldReference }
        val style = FixtureDex.classes(bundle, setOf(clip.definingClass)).getValue(clip.definingClass)
        val token = clip.type
        val tokens = FixtureDex.classes(bundle, setOf(token)).getValue(token)
        val surface = enumConstant(tokens, "SURFACE_BACKGROUND")
        val transparent = enumConstant(tokens, "ALWAYS_TRANSPARENT")
        assertTrue("$name: no SURFACE_BACKGROUND or ALWAYS_TRANSPARENT token", surface != null && transparent != null)
        val styleInit = style.methods.single { it.name == "<clinit>" }.implementation!!.instructions.map { it.reference() }
        assertTrue("$name: the bling bar style reads no SURFACE_BACKGROUND", surface in styleInit)
        assertTrue("$name: the bling bar style reads no ALWAYS_TRANSPARENT", transparent in styleInit)

        val context = PatchContexts.of(listOf(component))
        context.hookReactionRing()
        context.hookReactionRing()
        val method = context.mutableClassDefBy(component.type).methods.single {
            it.name == factory.name && it.parameterTypes.map(Any::toString) == factory.parameterTypes.map(Any::toString)
        }
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$name: two instructions added, once", original.size + 2, patched.size)
        val hook = patched[read + 1]
        assertEquals("$name: a range call", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
        assertEquals("$name: to the extension", REACTION_RING, hook.reference())
        val call = hook as RegisterRangeInstruction
        assertEquals("$name: on the read's register", listOf(register, 1), listOf(call.startRegister, call.registerCount))
        assertEquals("$name: the answer is kept", Opcode.MOVE_RESULT, patched[read + 2].opcode)
        assertEquals("$name: in the same register", register, (patched[read + 2] as OneRegisterInstruction).registerA)
        assertEquals("$name: Facebook's code stays", original.map { it.opcode },
            patched.take(read + 1).map { it.opcode } + patched.drop(read + 3).map { it.opcode })
    }

    /**
     * The second facepile (#147): PillsBlingBarView makes one Drawable from a Context alone, the
     * reactions token pile, whose constructor sets one Paint colour and whose draw paints a disc of it
     * behind each icon unless its maker switched it to cutting. The hook hands that colour to the
     * extension right before it's set, and the other theme's second call leaves it as it is.
     */
    @Test
    fun `the reactions token pile's disc colour goes through the extension on every declared build`() = bundles { bundle ->
        val name = bundle.name
        val bar = FixtureDex.classes(bundle, setOf(PILLS_BLING_BAR))[PILLS_BLING_BAR]
        assertTrue("$name: no $PILLS_BLING_BAR", bar != null)
        val made = reactionPiles(bar!!) { "Landroid/graphics/drawable/Drawable;" }
        val classes = FixtureDex.classes(bundle, made.toSet())
        val piles = reactionPiles(bar) { classes[it]?.superclass }
        assertEquals("$name: Drawables $PILLS_BLING_BAR makes from a Context", 1, piles.size)
        val pile = classes.getValue(piles.single())
        assertTrue("$name: the token pile paints no disc", pile.calls("Landroid/graphics/Canvas;", "drawCircle"))
        val constructor = pile.methods.single { it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf("Landroid/content/Context;") }
        val original = constructor.implementation!!.instructions.toList()
        val at = pileColourSets(constructor).single()
        val colour = (original[at] as FiveRegisterInstruction).registerD

        val context = PatchContexts.of(listOf(bar, pile))
        context.hookReactionPile()
        context.hookReactionPile()
        val method = context.mutableClassDefBy(pile.type).methods.single { it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf("Landroid/content/Context;") }
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$name: two instructions added, once", original.size + 2, patched.size)
        assertEquals("$name: a range call", Opcode.INVOKE_STATIC_RANGE, patched[at].opcode)
        assertEquals("$name: to the extension", REACTION_PILE, patched[at].reference())
        val call = patched[at] as RegisterRangeInstruction
        assertEquals("$name: on the colour's register", listOf(colour, 1), listOf(call.startRegister, call.registerCount))
        assertEquals("$name: the answer is kept", colour, (patched[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$name: then Facebook sets it", PILE_SETS_COLOUR, patched[at + 2].reference())
        assertEquals("$name: Facebook's code stays", original.map { it.opcode },
            patched.take(at).map { it.opcode } + patched.drop(at + 2).map { it.opcode })
    }
}
