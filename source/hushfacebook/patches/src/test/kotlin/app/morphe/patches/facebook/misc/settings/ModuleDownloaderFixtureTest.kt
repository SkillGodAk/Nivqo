/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.settings

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.PatchLogCapture
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #168's downloader fix on each declared build: the download manager's second flag test, the one
 * that falls through to Google Play and jumps to Facebook's own downloader, tests the extension's
 * answer instead of the flag it read. The two constants are checked by the names the enum gives
 * them, and nothing else in the method changes. Every build carries the fix through Hushfacebook
 * settings.
 */
class ModuleDownloaderFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun Method.code() = implementation!!.instructions.toList()

    private fun Method.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    /** The enum's constants by field: each sput-object of the enum's own type, named by the last string before it. */
    private fun constantNames(enum: ClassDef): Map<String, String> {
        val names = mutableMapOf<String, String>()
        var last: String? = null
        for (instruction in enum.methods.single { it.name == "<clinit>" }.code()) {
            ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.let { last = it.string }
            val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
            if (instruction.opcode == Opcode.SPUT_OBJECT && field?.type == enum.type && last != null) names[field.name] = last!!
        }
        return names
    }

    @Test
    fun `each declared build's download manager asks the extension before choosing Google Play`() {
        val fromPlay = ExtensionDex.classDef(MODULE_DOWNLOADS).methods.single { it.name == "fromPlay" }
        assertTrue("fromPlay is public and static", AccessFlags.PUBLIC.isSet(fromPlay.accessFlags) && AccessFlags.STATIC.isSet(fromPlay.accessFlags))
        assertEquals(listOf("Z"), fromPlay.parameterTypes.map(CharSequence::toString))
        assertEquals("Z", fromPlay.returnType)

        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val owners = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { holdsString(it, NO_DOWNLOADER) }) owners += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(owners)
                val site = context.findPlayChoiceSite()
                val stock = site.method.code()

                // The site tests the second flag read, straight after it.
                val read = stock[site.index - 1]
                assertEquals("$name: a flag is read", Opcode.IGET_BOOLEAN, read.opcode)
                assertEquals("$name: into the tested register", site.register, (read as TwoRegisterInstruction).registerA)
                assertEquals("$name: and tested", Opcode.IF_EQZ, stock[site.index].opcode)

                // Its jump lands on SIDELOAD and the fall-through reads GOOGLE_PLAY, by the enum's own names.
                val address = IntArray(stock.size + 1)
                for (at in stock.indices) address[at + 1] = address[at] + stock[at].codeUnits
                val jump = address.indexOf(address[site.index] + (stock[site.index] as OffsetInstruction).codeOffset)
                val own = (stock[jump] as ReferenceInstruction).reference as FieldReference
                val play = (stock[site.index + 1] as ReferenceInstruction).reference as FieldReference
                val enum = FixtureDex.classes(bundle, setOf(own.type)).values.single()
                val names = constantNames(enum)
                assertEquals("$name: the jump picks Facebook's own downloader", "SIDELOAD", names[own.name])
                assertEquals("$name: the fall-through picks Google Play", "GOOGLE_PLAY", names[play.name])

                assertEquals("$name: warned", emptyList<String>(), PatchLogCapture.warnings { moduleDownloaderPatch.execute(context) })
                val patched = context.mutableClassDefBy(site.method.definingClass).methods.single { it.descriptor() == site.method.descriptor() }
                val code = patched.code()
                val call = code[site.index]
                assertEquals("$name: the extension is asked", FROM_PLAY, (call as ReferenceInstruction).reference.toString())
                assertEquals("$name: with the flag's register", site.register, (call as RegisterRangeInstruction).startRegister)
                assertEquals(1, call.registerCount)
                assertEquals("$name: its answer is kept", Opcode.MOVE_RESULT, code[site.index + 1].opcode)
                assertEquals("$name: in the flag's register", site.register, (code[site.index + 1] as OneRegisterInstruction).registerA)
                assertEquals("$name: and tested", Opcode.IF_EQZ, code[site.index + 2].opcode)
                assertEquals("$name: nothing else moved", stock.size + 2, code.size)
                assertEquals("$name: no register added", site.method.implementation!!.registerCount, patched.implementation!!.registerCount)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** No selection leaves the fix out: it has no name to deselect and Hushfacebook settings depends on it. */
    @Test
    fun `Hushfacebook settings brings the fix into every build`() {
        assertNull("a named patch can be deselected", moduleDownloaderPatch.name)
        assertTrue(settingsPatch.dependencies.any { it === moduleDownloaderPatch })
    }
}
