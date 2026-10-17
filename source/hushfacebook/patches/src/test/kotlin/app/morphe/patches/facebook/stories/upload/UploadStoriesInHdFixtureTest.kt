/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.stories.upload

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Upload Stories in HD on each declared Facebook build: the publisher's one putString of a Story
 * media's "video_upload_quality" asks the extension first, into the value's own register, and the
 * rest of the method stays as Facebook wrote it. SettingsStatus says the patch is in.
 */
class UploadStoriesInHdFixtureTest {
    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun same(a: Method, b: Method) = a.name == b.name && a.returnType == b.returnType &&
        a.parameterTypes.map(CharSequence::toString) == b.parameterTypes.map(CharSequence::toString)

    @Test
    fun `each declared build's Story upload asks the extension for its quality`() = bundles { bundle ->
        val holders = FixtureDex.classesHolding(bundle, VIDEO_UPLOAD_QUALITY)
        // Probed on the arm64 582 build (2026-10-14): the composer's and the publish model's
        // serializers, the feed publisher and the video uploader hold the key too, and only the
        // publisher's builder of a new post's upload has the kept name.
        val publishers = holders.flatMap { owner -> owner.methods.filter(::isUploadForCreate) }
        assertEquals("${bundle.name}: the publisher's $UPLOAD_FOR_CREATE", 1, publishers.size)
        val publisher = publishers.single()
        assertTrue("${bundle.name}: the builder reads a Story destination", publisher.readsStoryDestination())

        val put = publisher.qualityPut()
        val code = publisher.implementation!!.instructions.toList()
        val original = code[put.put] as FiveRegisterInstruction
        assertEquals("${bundle.name}: the put", PUT_STRING, (original as ReferenceInstruction).reference.toString())
        val key = code.subList(0, put.put).last {
            it is OneRegisterInstruction && it.registerA == original.registerD && it.opcode != Opcode.MOVE_RESULT_OBJECT
        }
        assertEquals("${bundle.name}: the key", VIDEO_UPLOAD_QUALITY,
            ((key as ReferenceInstruction).reference as StringReference).string)

        val context = PatchContexts.of(holders + ExtensionDex.classDef(SETTINGS_STATUS))
        uploadStoriesInHdPatch.execute(context)

        val body = context.mutableClassDefBy(publisher.definingClass).methods.single { same(it, publisher) }
            .implementation!!.instructions.toList()
        val where = "${bundle.name}: ${publisher.definingClass}->${publisher.name}"
        assertEquals("$where: two instructions added", code.size + 2, body.size)
        for (at in 0 until put.put) assertEquals("$where: instruction $at", code[at].opcode, body[at].opcode)
        val ask = body[put.put]
        assertEquals("$where: the ask's form", Opcode.INVOKE_STATIC_RANGE, ask.opcode)
        assertEquals("$where: the ask", QUALITY_CALL, (ask as ReferenceInstruction).reference.toString())
        assertEquals("$where: the ask reads the value", put.value, (ask as RegisterRangeInstruction).startRegister)
        assertEquals("$where: the ask reads one register", 1, ask.registerCount)
        val answer = body[put.put + 1]
        assertEquals("$where: the answer's form", Opcode.MOVE_RESULT_OBJECT, answer.opcode)
        assertEquals("$where: the answer's register", put.value, (answer as OneRegisterInstruction).registerA)
        val handed = body[put.put + 2] as FiveRegisterInstruction
        assertEquals("$where: the put after", PUT_STRING, (handed as ReferenceInstruction).reference.toString())
        assertEquals("$where: the put's registers",
            listOf(original.registerC, original.registerD, original.registerE),
            listOf(handed.registerC, handed.registerD, handed.registerE))
        for (at in put.put + 1 until code.size) {
            assertEquals("$where: instruction $at", code[at].opcode, body[at + 2].opcode)
        }

        val status = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == "storyUploadHd" }
            .implementation!!.instructions.toList()
        assertEquals("${bundle.name}: SettingsStatus answers first", listOf(Opcode.CONST_4, Opcode.RETURN),
            status.take(2).map { it.opcode })
        assertEquals("${bundle.name}: SettingsStatus says the patch is in", 1L,
            (status.first() as WideLiteralInstruction).wideLiteral)
    }
}
