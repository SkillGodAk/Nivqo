/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
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
 * resource and the token to Material You first, then returns what comes back.
 */
class TokenResourceLookupFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun bundles(version: String) = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.call(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    @Test
    fun `FDS's token lookup is found and each answer goes to the extension with its token`() {
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

                    with(context) { lookup.hookColorReturns(tokenParameterIndex = 1, target = TOKEN_RESOURCE) }
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
                } finally {
                    FdsSchemeResolveFingerprint.clearMatch()
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
