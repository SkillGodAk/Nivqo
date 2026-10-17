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
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide suggested and promoted posts' notification post footer hook (#163) on every Facebook build
 * the bundle declares: one method outside the extension sets "show_notification_permalink_footer",
 * three times from a MobileConfig boolean, and that method takes a FetchSingleStoryParams, the
 * single-post fetch a notification opens. The patch hands each boolean to the extension as it comes
 * back, in its own register. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class NotificationFooterFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

    @Test
    fun `each declared build asks for the footer through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = FixtureDex.classesHolding(bundle, NOTIFICATION_FOOTER_FLAG)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val holders = context.footerFlagHolders()
                assertEquals("$name: methods setting \"$NOTIFICATION_FOOTER_FLAG\"", 1, holders.size)
                val method = holders.single()
                // Positive control: this is the single-post query a notification's post is fetched with.
                assertTrue("$name: ${method.name} doesn't take a FetchSingleStoryParams",
                    method.parameterTypes.any { it.toString() == "Lcom/facebook/api/story/FetchSingleStoryParams;" })
                val answers = footerFlagAnswers(method)
                assertNotNull("$name: the flag isn't set from a boolean answer", answers)
                // 582 sets it once per query variant, two boxed and one raw.
                assertEquals("$name: places the flag is set", 3, answers!!.size)
                val code = method.code()
                val boxed = answers.count { code[it + 1].called()?.toString() == "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;" }
                assertEquals("$name: boxed before the name", 2, boxed)

                context.hookNotificationFooter()
                val patched = context.mutableClassDefBy(method.definingClass).methods
                    .single { patchedMethod ->
                        patchedMethod.name == method.name &&
                            patchedMethod.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                    }.code()
                assertEquals("$name: two instructions a place", code.size + 2 * answers.size, patched.size)
                answers.forEachIndexed { done, answer ->
                    val at = answer + 1 + 2 * done
                    val register = (code[answer] as OneRegisterInstruction).registerA
                    val ask = patched[at]
                    assertEquals("$name: the extension asked at $answer", NOTIFICATION_FOOTER, ask.called()?.toString())
                    assertEquals("$name: handed the boolean at $answer",
                        listOf(Opcode.INVOKE_STATIC_RANGE, register, 1),
                        listOf(ask.opcode, (ask as RegisterRangeInstruction).startRegister, ask.registerCount))
                    assertEquals("$name: its answer back in the same register at $answer",
                        listOf(Opcode.MOVE_RESULT, register),
                        listOf(patched[at + 1].opcode, (patched[at + 1] as OneRegisterInstruction).registerA))
                    assertEquals("$name: Facebook's answer right before at $answer",
                        listOf(Opcode.MOVE_RESULT, register),
                        listOf(patched[at - 1].opcode, (patched[at - 1] as OneRegisterInstruction).registerA))
                }
                val asked = patched.withIndex().filterNot { (index, _) ->
                    answers.indices.any { done -> index - (answers[done] + 1 + 2 * done) in 0..1 }
                }.map { it.value.opcode }
                assertEquals("$name: the rest is Facebook's", code.map { it.opcode }, asked)
                assertEquals("$name: the name is still set three times", 3, patched.count {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == NOTIFICATION_FOOTER_FLAG
                })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hook`() {
        val type = NOTIFICATION_FOOTER.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "show" }
        assertNotNull("the extension has no NotificationFooter.show", hook)
        assertEquals("show's shape", NOTIFICATION_FOOTER,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("show isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
