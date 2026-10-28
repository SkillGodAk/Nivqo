/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.pip

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #126, a link opened while a reel floats: on each declared build, the classes logging
 * Facebook's NEW_TASK warning have one launch step, a static (Context, Intent, its class, Integer)
 * -> Intent method, which the link handler's task stack calls with its main screen marked by the
 * extra the extension reads. The patch puts the extension first in it, with the context and the
 * intent from p0 and p1.
 */
class PictureInPictureLaunchStepFixtureTest {
    /** The extra marking Facebook's main screen built under a page; PictureInPicture.UNDER_A_PAGE. */
    private val underAPage = "launched_in_background_of_activity_stack"

    private fun Method.calls(step: Method) = implementation!!.instructions.any {
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        call?.definingClass == step.definingClass && call.name == step.name &&
            call.parameterTypes.map(CharSequence::toString) == step.parameterTypes.map(CharSequence::toString)
    }

    @Test
    fun `each declared build has one launch step, and the extension sees each start first`() {
        val extension = ExtensionDex.classDef(PICTURE_IN_PICTURE)
        assertTrue("the extension reads no \"$underAPage\"", extension.methods.any { it.name == "ownTask" && holdsString(it, underAPage) })

        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val holders = FixtureDex.classesHolding(bundle, LAUNCH_WARNING).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val steps = holders.flatMap { holder -> holder.methods.filter(::isLaunchStep) }
                assertEquals("$name: launch steps in classes logging the warning", 1, steps.size)
                val step = steps.single()
                val registers = step.implementation!!.registerCount
                assertTrue("$name: the step's context and intent sit past v15", registers - 3 <= 15)
                // 584 asks a string table for the extra, so the table's callers that start the step count too.
                val underAPageHolders = FixtureDex.classesHolding(bundle, underAPage)
                val tables = underAPageHolders.associateBy { it.type }
                val resolve: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
                val builders = underAPageHolders.flatMap { holder -> holder.methods.filter { holdsString(it, underAPage) } } +
                    FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.definingClass in tables } }) {
                        it.implementation != null && it.calls(step) && !holdsString(it, underAPage) && namesString(it, underAPage, resolve)
                    }
                assertTrue("$name: no method building the main screen under a page calls the step; those building it: " +
                    builders.joinToString { "${it.definingClass}->${it.name}" },
                    builders.any { it.calls(step) })
                val passing = holders.single { it.type == step.definingClass }.methods.count { it.calls(step) }
                assertTrue("$name: only $passing of its class's starts pass the step", passing >= 3)

                val context = PatchContexts.of(holders + extension)
                context.applyOwnTask(context.findLaunchStep())

                val patched = context.mutableClassDefBy(step.definingClass).methods.single {
                    it.name == step.name && isLaunchStep(it)
                }.implementation!!.instructions.toList()
                assertEquals("$name: one instruction added", step.implementation!!.instructions.count() + 1, patched.size)
                val first = patched[0]
                assertEquals("$name: the extension isn't first", Opcode.INVOKE_STATIC, first.opcode)
                assertEquals(OWN_TASK, ((first as ReferenceInstruction).reference as MethodReference).toString())
                val hook = first as FiveRegisterInstruction
                assertEquals("$name: the hook doesn't get p0 and p1", listOf(registers - 4, registers - 3),
                    listOf(hook.registerC, hook.registerD))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
