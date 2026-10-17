/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

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
 * Clean up Reels' Keep the original audio hook (#148) on every Facebook build the bundle declares:
 * FbVpsController stores one audio language supplier after reading enableAudioDubbingSupport, its
 * holder has one synchronized getter for it, and the getter hands its answer to the extension right
 * after casting it, with its lock still held. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class OriginalAudioFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    @Test
    fun `each declared build's audio language getter answers through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val controller = FixtureDex.classes(bundle, setOf(VPS_CONTROLLER))[VPS_CONTROLLER]
                assertNotNull("$name: no $VPS_CONTROLLER", controller)
                val supplier = audioLanguageSupplier(controller!!)
                assertNotNull("$name: no one supplier stored after reading $DUBBING_SUPPORT", supplier)
                val holder = FixtureDex.classes(bundle, setOf(supplier!!.definingClass)).getValue(supplier.definingClass)
                val getters = audioLanguageGetters(holder, supplier)
                assertEquals("$name: getters for the audio language", 1, getters.size)
                val getter = getters.single()
                assertTrue("$name: the getter isn't synchronized",
                    AccessFlags.DECLARED_SYNCHRONIZED.isSet(getter.accessFlags) ||
                        getter.code().any { it.opcode == Opcode.MONITOR_ENTER })
                val cast = supplierAnswer(getter, supplier)
                assertNotNull("$name: the getter doesn't cast its supplier's answer to a String once", cast)
                val code = getter.code()
                val register = (code[cast!!] as OneRegisterInstruction).registerA

                // Positive control: this is the language the DASH manifest parser picks a video's audio
                // set by. It asks the getter, then checks the answer against "device", the value
                // that means the phone's own language.
                val askers = FixtureDex.classesHolding(bundle, "device")
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                    .flatMap { it.methods }
                    .filter { method ->
                        val body = method.implementation?.instructions?.toList() ?: return@filter false
                        body.indices.any { i ->
                            body[i].called()?.let { it.definingClass == holder.type && it.name == getter.name } == true &&
                                body.drop(i + 1).take(3).any { it.string() == "device" }
                        }
                    }
                assertTrue("$name: nothing checks the getter's answer against \"device\"", askers.isNotEmpty())

                val context = PatchContexts.of(listOf(controller, holder))
                context.hookOriginalAudio()
                context.hookOriginalAudio()
                val patched = context.mutableClassDefBy(holder.type).methods
                    .single { it.name == getter.name && it.parameterTypes.isEmpty() }.code()
                assertEquals("$name: two instructions, once", code.size + 2, patched.size)
                assertEquals("$name: Facebook's cast stays", Opcode.CHECK_CAST, patched[cast].opcode)
                val ask = patched[cast + 1]
                assertEquals("$name: the cast is followed by the extension", ORIGINAL_AUDIO, ask.called()?.toString())
                assertEquals("$name: handed the language",
                    listOf(Opcode.INVOKE_STATIC_RANGE, register, 1),
                    listOf(ask.opcode, (ask as RegisterRangeInstruction).startRegister, ask.registerCount))
                assertEquals("$name: its answer back in the same register",
                    listOf(Opcode.MOVE_RESULT_OBJECT, register),
                    listOf(patched[cast + 2].opcode, (patched[cast + 2] as OneRegisterInstruction).registerA))
                assertEquals("$name: the lock is still held, then let go",
                    Opcode.MONITOR_EXIT, patched[cast + 3].opcode)
                assertEquals("$name: the rest is Facebook's",
                    code.drop(cast + 1).map { it.opcode }, patched.drop(cast + 3).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has the hook`() {
        val type = ORIGINAL_AUDIO.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "language" }
        assertNotNull("the extension has no OriginalAudio.language", hook)
        assertEquals("language's shape", ORIGINAL_AUDIO,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("language isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
