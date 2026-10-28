/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #37's Menu Log out row on each declared build: the row is a drawable of the colour resource
 * FDS's token lookup gives out for CARD_BACKGROUND_FLAT (582 `LX/24i;->A02`). The lookup is found on
 * the view resolver's class, apart from the resolver itself, and each of its returns hands the
 * resource and the token to Material You first, then returns what comes back. With AMOLED in the
 * build, AMOLED's hook goes in first, with the lookup's context too (#191).
 */
class TokenResourceLookupFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun bundles(version: String) = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.call(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    /** The body without the NOPs an insert can add to align a switch's payload. */
    private fun List<Instruction>.code() = filter { it.opcode != Opcode.NOP }

    /** Runs [inspect] on the lookup of each declared build's bundles, with a context holding its classes. */
    private fun forEachLookup(inspect: BytecodePatchContext.(name: String, lookup: MutableMethod) -> Unit) {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                val resolverClass = fdsDarkCheckOf(bundle).substringBefore("->")
                val scheme = FixtureDex.classes(bundle, setOf(FDS_COLOR_SCHEME)).values
                val resolver = FixtureDex.classes(bundle, setOf(resolverClass)).values
                val callees = resolver.flatMap { it.methods }.flatMap { method -> method.body().mapNotNull { it.call()?.definingClass } }
                    .toSet() - resolverClass - FDS_COLOR_SCHEME
                val helpers = FixtureDex.classes(bundle, callees).values

                FdsSchemeResolveFingerprint.clearMatch()
                try {
                    val context = PatchContexts.of(scheme + resolver + helpers)
                    val lookup = with(context) { fdsTokenResourceLookup() }
                    assertEquals("$name: on the view resolver's class", resolverClass, lookup.definingClass)
                    assertNotEquals("$name: not the resolver beside it", with(context) { fdsViewResolver() }.name, lookup.name)
                    context.inspect(name, lookup)
                } finally {
                    FdsSchemeResolveFingerprint.clearMatch()
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `FDS's token lookup is found and each answer goes to the extension with its token`() = forEachLookup { name, lookup ->
        lookup.hookColorReturns(tokenParameterIndex = 1, target = TOKEN_RESOURCE)
        val after = lookup.body()
        val token = lookup.implementation!!.registerCount - 1
        val returns = after.withIndex().filter { it.value.opcode == Opcode.RETURN }
        assertTrue("$name: the lookup returns", returns.isNotEmpty())
        for ((index, ret) in returns) {
            val register = (ret as OneRegisterInstruction).registerA
            val call = after[index - 2]
            assertEquals("$name: return $index goes to the extension first", TOKEN_RESOURCE, call.call()?.toString())
            assertEquals("$name: with the resource", register, (call as FiveRegisterInstruction).registerC)
            assertEquals("$name: and the token", token, call.registerD)
            assertEquals("$name: then returns its answer", Opcode.MOVE_RESULT, after[index - 1].opcode)
            assertEquals("$name: in the same register", register, (after[index - 1] as OneRegisterInstruction).registerA)
        }
    }

    /**
     * Black cards (#191): a card's resource and a button's are one resource in Facebook's dark style,
     * so AMOLED answers at the lookup, where the token still tells them apart. Its hook takes the
     * resource, the context and the token, and Material You's, put in after it as the theme's finalize
     * does, takes AMOLED's answer.
     */
    @Test
    fun `AMOLED answers each lookup first with its context and token, and Material You gets that answer`() = forEachLookup { name, lookup ->
        val before = lookup.body()
        lookup.hookTokenResourceReturns(TOKEN_RESOURCE_DARK)
        lookup.hookColorReturns(tokenParameterIndex = 1, target = TOKEN_RESOURCE)
        val after = lookup.body()
        val token = lookup.implementation!!.registerCount - 1
        val context = token - 1
        val returns = after.withIndex().filter { it.value.opcode == Opcode.RETURN }
        assertEquals("$name: four instructions before each return", before.code().size + 4 * returns.size, after.code().size)
        for ((index, ret) in returns) {
            val register = (ret as OneRegisterInstruction).registerA
            val amoled = after[index - 4] as FiveRegisterInstruction
            assertEquals("$name: return $index goes to AMOLED first", TOKEN_RESOURCE_DARK, after[index - 4].call()?.toString())
            assertEquals("$name: with the resource, the context and the token", listOf(register, context, token),
                listOf(amoled.registerC, amoled.registerD, amoled.registerE))
            assertEquals("$name: whose answer it keeps", Opcode.MOVE_RESULT, after[index - 3].opcode)
            assertEquals("$name: in the same register", register, (after[index - 3] as OneRegisterInstruction).registerA)
            assertEquals("$name: then Material You", TOKEN_RESOURCE, after[index - 2].call()?.toString())
            assertEquals("$name: with AMOLED's answer", register, (after[index - 2] as FiveRegisterInstruction).registerC)
        }
    }
}
