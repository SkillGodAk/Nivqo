/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.carrierbanners

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #70: on each declared build, Facebook Flex's banner method has to meet at one point after
 * every banner it shows, with the banner's key in hand, and the hook has to go in there without
 * becoming a branch target: a branch into the snippet's middle would skip the question, and one
 * to its start would be a second way in. The keys CarrierBanners sorts into Data mode and Free mode
 * have to be the ones the method sets, and the hide call the one it makes for no banner.
 */
class CarrierBannersFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    @After
    fun forgetTheLastMatch() = CarrierBannerFingerprint.clearMatch()

    @Test
    fun `every banner Facebook Flex shows meets the hook, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, SWITCHER_START).filter { classDef ->
                    classDef.methods.any { holdsString(it, SWITCHER_START) && holdsString(it, SWITCHER_END) }
                }
                assertEquals("$name: one class holds the banner method", 1, owners.size)
                val owner = owners.single()
                val method = owner.methods.single { holdsString(it, SWITCHER_START) && holdsString(it, SWITCHER_END) }
                val body = method.body()

                // Every key CarrierBanners knows is one the method sets.
                val strings = body.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()
                for (key in DATA_MODE + FREE_MODE) assertTrue("$name: the method never names $key", key in strings)

                val join = bannerJoin(method)
                assertEquals("$name: the key is the method's own field", owner.type, join.keyField.definingClass)
                assertTrue("$name: the hide call is the manager's", join.hide.definingClass == owner.type)
                assertTrue("$name: the hook goes in before the log of a shown banner",
                    join.keyRead < body.indexOfFirst { it.isString(SWITCHER_END) })
                assertEquals("$name: nothing branches to where the hook goes in", emptyList<Int>(),
                    targets(body).filter { it == join.keyRead + 1 })
                // The branches that set a banner up meet at the read: most jump there, one falls in.
                val meeting = targets(body).count { it == join.keyRead }
                assertTrue("$name: only $meeting branches meet at the key's read", meeting >= 5)

                CarrierBannerFingerprint.clearMatch()
                val context = PatchContexts.of(listOf(owner))
                with(context) { hookCarrierBanners() }
                val after = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == method.descriptor() }.body()
                val hook = after[join.keyRead + 1]
                assertEquals("$name: the key goes to CarrierBanners.hides", HIDES,
                    ((hook as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: from the register it was read into", join.keyRegister, (hook as RegisterRangeInstruction).startRegister)
                assertEquals("$name: a yes calls Facebook's hide", join.hide.toString(),
                    ((after[join.keyRead + 4] as ReferenceInstruction).reference as MethodReference).toString())
                val reread = after[join.keyRead + 6]
                assertEquals("$name: a no reads the key again", Opcode.IGET_OBJECT, reread.opcode)
                assertEquals("$name: from the same field", join.keyField.toString(),
                    ((reread as ReferenceInstruction).reference as FieldReference).toString())
                assertEquals("$name: then the method goes on as it was", body[join.keyRead + 1].opcode, after[join.keyRead + 7].opcode)
                assertEquals("$name: six instructions added", body.size + 6, after.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Instruction.isString(value: String) = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string == value

    /** The instruction indexes every branch and switch in [body] can land on. */
    private fun targets(body: List<Instruction>): List<Int> {
        val addresses = IntArray(body.size)
        var address = 0
        for ((index, instruction) in body.withIndex()) {
            addresses[index] = address
            address += instruction.codeUnits
        }
        val indexAt = addresses.withIndex().associate { (index, at) -> at to index }
        val found = mutableListOf<Int>()
        for ((index, instruction) in body.withIndex()) {
            // Opcode.name is the smali mnemonic. The method has no switch, so gotos and ifs are all.
            val branch = instruction.opcode.name.startsWith("goto") || instruction.opcode.name.startsWith("if-")
            if (instruction is OffsetInstruction && branch) indexAt[addresses[index] + instruction.codeOffset]?.let(found::add)
        }
        assertTrue("the banner method has a switch", body.none { it is SwitchPayload })
        return found
    }

    private companion object {
        /** CarrierBanners.DATA_MODE. */
        val DATA_MODE = setOf("zorp_paid_mode_banner", "autoflex_paid_mode_banner", "autoflex_paid_mode_banner_b4o")

        /** CarrierBanners.FREE_MODE. */
        val FREE_MODE = setOf("autoflex_banner", "freemium_models_banner", "freemium_models_fup_banner")
    }
}
