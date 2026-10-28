/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide suggested and promoted posts' Find friends card hook (#187) on every Facebook build the bundle
 * declares: one method outside the extension builds the section naming itself "EndOfFeedCIUnitSection"
 * behind a String it reads and tests for null, and that method is the end of feed section's builder,
 * which also adds the feed's closing spinner. The patch hands the String to the extension right after
 * the read, in its own register. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips
 * without it.
 */
class FindFriendsCardFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    @Test
    fun `each declared build reads the friends card's string through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = FixtureDex.classesHolding(bundle, FRIENDS_CARD_SECTION)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val builders = context.friendsCardBuilders()
                assertEquals("$name: methods building \"$FRIENDS_CARD_SECTION\" behind a string", 1, builders.size)
                val method = builders.single()
                val code = method.code()
                // Positive control: the end of the feed's builder, which adds its closing spinner too.
                assertTrue("$name: ${method.name} doesn't add the end of feed spinner",
                    code.any { it.string() == "EndOfFeedSpinnerSection" })
                val read = friendsCardGuard(method)
                assertNotNull("$name: no guarded read", read)
                val register = (code[read!!] as TwoRegisterInstruction).registerA
                assertEquals("$name: reads a String", "Ljava/lang/String;",
                    ((code[read] as ReferenceInstruction).reference as FieldReference).type)
                // The card is built from that same String: it's stored on the new section.
                val created = read + 2
                val card = ((code[created] as ReferenceInstruction).reference as TypeReference).type
                assertTrue("$name: the String isn't handed to the card", code.subList(created, created + 6).any {
                    it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == register &&
                        ((it as ReferenceInstruction).reference as FieldReference).definingClass == card
                })

                context.hookFindFriendsCard()
                val patched = context.mutableClassDefBy(method.definingClass).methods
                    .single { patchedMethod ->
                        patchedMethod.name == method.name &&
                            patchedMethod.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                    }.code()
                assertEquals("$name: two instructions added", code.size + 2, patched.size)
                val ask = patched[read + 1]
                assertEquals("$name: the extension asked", FRIENDS_CARD,
                    ((ask as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: handed the String",
                    listOf(Opcode.INVOKE_STATIC_RANGE, register, 1),
                    listOf(ask.opcode, (ask as RegisterRangeInstruction).startRegister, ask.registerCount))
                assertEquals("$name: its answer back in the same register",
                    listOf(Opcode.MOVE_RESULT_OBJECT, register),
                    listOf(patched[read + 2].opcode, (patched[read + 2] as OneRegisterInstruction).registerA))
                assertEquals("$name: Facebook's null test right after",
                    listOf(Opcode.IF_EQZ, register),
                    listOf(patched[read + 3].opcode, (patched[read + 3] as OneRegisterInstruction).registerA))
                assertEquals("$name: the rest is Facebook's", code.map { it.opcode },
                    patched.filterIndexed { index, _ -> index != read + 1 && index != read + 2 }.map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hook`() {
        val type = FRIENDS_CARD.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "unit" }
        assertNotNull("the extension has no FindFriendsCard.unit", hook)
        assertEquals("unit's shape", FRIENDS_CARD,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("unit isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
