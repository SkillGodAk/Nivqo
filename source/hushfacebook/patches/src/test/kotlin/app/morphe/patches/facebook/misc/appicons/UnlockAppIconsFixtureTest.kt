/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.appicons

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.settings.CREATE_WORDMARK_VIEW
import app.morphe.patches.facebook.misc.settings.LOGO_TOUCH_STAND_IN
import app.morphe.patches.facebook.misc.settings.WORDMARK_NAVIGATION_BAR
import app.morphe.patches.facebook.misc.settings.logoTouchListenerIndex
import app.morphe.patches.facebook.misc.settings.sendToStandIn
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import app.morphe.util.writersReaching
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unlock app icons' anchors on every declared Facebook build: one benefit provider with one check of
 * a benefit name, which the App icon page and the start-up job both ask for [BENEFIT], and one App
 * icon page callback that looks for [BENEFIT] in each synced set, and one component manager state
 * call. Then the four hooks go in on the real classes, which also assembles their smali.
 */
class UnlockAppIconsFixtureTest {
    @Test
    fun `the header hook is public and static in the extension`() {
        val calls = ExtensionDex.classDef("Lapp/morphe/extension/facebook/misc/AppIcons;").methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("the extension declares no public static $DRESS_LOGO: $calls", DRESS_LOGO in calls)
    }

    @Test
    fun `each declared build has the benefit check and the App icon page's look once`() {
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

    private fun check(bundle: File) {
        val name = bundle.name
        val providers = FixtureDex.classesHolding(bundle, PROVIDER_TAG).filter(::isBenefitProvider)
        assertEquals("$name: benefit providers", 1, providers.size)
        val provider = providers.single()
        val checks = provider.methods.filter(::isBenefitCheck)
        assertEquals("$name: benefit checks in ${provider.type}", 1, checks.size)
        val check = checks.single()

        // Some class that loads the benefit name calls the check (on 582, the start-up job that puts the default icon back).
        val askers = FixtureDex.classesHolding(bundle, BENEFIT)
        val asking = askers.filter { classDef ->
            classDef.methods.any { method ->
                method.implementation?.instructions?.any { it.calls(provider.type, check.name) } == true
            }
        }
        assertTrue("$name: no class that loads $BENEFIT calls ${provider.type}->${check.name}", asking.isNotEmpty())

        val looks = askers.flatMap { classDef -> classDef.methods.mapNotNull { m -> pickerBenefitLook(m)?.let { Triple(classDef, m, it) } } }
        assertEquals("$name: App icon page looks at $BENEFIT", 1, looks.size)
        val (pageClass, callback, result) = looks.single()
        val code = callback.implementation!!.instructions.toList()
        val answer = (code[result] as OneRegisterInstruction).registerA

        // Both hooks go in on the real classes.
        val context = PatchContexts.of(listOf(provider, pageClass))
        context.mutableClassDefBy(provider.type).findMutableMethodOf(check).unlockFirst()
        context.mutableClassDefBy(pageClass.type).findMutableMethodOf(callback).passPickerBenefit(result)

        val gate = context.mutableClassDefBy(provider.type).findMutableMethodOf(check).implementation!!.instructions.toList()
        assertEquals("$name: the check's first call", UNLOCKED, gate[0].call())
        assertEquals("$name: the unlocked answer", Opcode.RETURN, gate[4].opcode)
        assertEquals("$name: the check's own code after the hook", check.implementation!!.instructions.count() + 5, gate.size)

        val patched = context.mutableClassDefBy(pageClass.type).findMutableMethodOf(callback).implementation!!.instructions.toList()
        val hook = patched[result + 1]
        assertEquals("$name: the call after the page's look", ENTITLED, hook.call())
        assertEquals("$name: the register handed over", answer, (hook as RegisterRangeInstruction).startRegister)
        assertEquals("$name: the answer goes back where it came from", answer, (patched[result + 2] as OneRegisterInstruction).registerA)
        assertEquals("$name: the page's own code after the hook", code.size + 2, patched.size)

        // The component manager sets every state with one call, which goes through the extension with the same registers.
        val resets = FixtureDex.classesHolding(bundle, COMPONENT_MANAGER_KEY)
            .flatMap { classDef -> classDef.methods.flatMap { m -> componentStateCalls(m).map { Triple(classDef, m, it) } } }
        assertEquals("$name: component manager state calls", 1, resets.size)
        val (managerClass, manager, at) = resets.single()
        val before = manager.implementation!!.instructions.toList()
        val registers = (before[at] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD, it.registerE, it.registerF) }
        val managerContext = PatchContexts.of(listOf(managerClass))
        managerContext.mutableClassDefBy(managerClass.type).findMutableMethodOf(manager).passComponentState(at)
        val after = managerContext.mutableClassDefBy(managerClass.type).findMutableMethodOf(manager).implementation!!.instructions.toList()
        assertEquals("$name: the manager's call", COMPONENT_STATE, after[at].call())
        assertEquals("$name: the manager's call is static", Opcode.INVOKE_STATIC, after[at].opcode)
        assertEquals("$name: the registers handed over", registers,
            (after[at] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD, it.registerE, it.registerF) })
        assertEquals("$name: the manager's own code around it", before.size, after.size)

        // Settings has already replaced the logo's touch call when this patch runs. The app icon
        // hook goes after Facebook loads the stock wordmark, using that same logo register.
        val bar = FixtureDex.classes(bundle, setOf(WORDMARK_NAVIGATION_BAR))[WORDMARK_NAVIGATION_BAR]
            ?: throw AssertionError("$name: no $WORDMARK_NAVIGATION_BAR")
        val builders = bar.methods.filter { holdsString(it, CREATE_WORDMARK_VIEW) }
        assertEquals("$name: logo builders", 1, builders.size)
        val logoBuilder = builders.single()
        val originalLogo = logoBuilder.implementation!!.instructions.toList()
        val touch = logoTouchListenerIndex(logoBuilder)
        val logo = originalLogo[touch].registers().first()
        val logoContext = PatchContexts.of(listOf(bar))
        val mutableLogo = logoContext.mutableClassDefBy(WORDMARK_NAVIGATION_BAR).findMutableMethodOf(logoBuilder)
        mutableLogo.sendToStandIn(touch, LOGO_TOUCH_STAND_IN)
        val site = logoDressSite(mutableLogo)
        assertTrue("$name: the stock wordmark load follows the touch listener", site.stockLoad > touch)
        assertEquals("$name: the touch listener's logo register", logo, site.logo)
        val stockInstruction = mutableLogo.implementation!!.instructions[site.stockLoad]
        val stockLoad = stockInstruction.call()
        val builderArguments = (
            mutableLogo.implementation!!.registerCount - logoBuilder.parameterTypes.size until
                mutableLogo.implementation!!.registerCount
        ).toList()
        assertTrue("$name: the logo builder is static", AccessFlags.STATIC.isSet(logoBuilder.accessFlags))
        val stockOrigins = stockInstruction.registers().map { register ->
            objectOriginAt(mutableLogo, site.stockLoad, register)
        }
        assertEquals("$name: the stock wordmark uses the builder arguments", builderArguments, stockOrigins)
        mutableLogo.dressLogoAfter(site)
        val dressed = mutableLogo.implementation!!.instructions.toList()
        assertEquals("$name: one header call added", originalLogo.size + 1, dressed.size)
        assertEquals("$name: the stock wordmark still loads first", stockLoad, dressed[site.stockLoad].call())
        assertEquals("$name: the picked icon call", DRESS_LOGO, dressed[site.stockLoad + 1].call())
        assertEquals("$name: the picked icon call is static range", Opcode.INVOKE_STATIC_RANGE, dressed[site.stockLoad + 1].opcode)
        assertEquals("$name: the logo is handed over", listOf(logo), dressed[site.stockLoad + 1].registers())
    }

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    private fun objectOriginAt(method: Method, index: Int, register: Int): Int? {
        val instructions = method.implementation!!.instructions.toList()
        var before = index
        var current = register
        val seen = mutableSetOf<Pair<Int, Int>>()
        while (seen.add(before to current)) {
            val writers = method.writersReaching(before, current)
            if (writers == listOf(-1)) return current
            val writer = writers.singleOrNull()?.takeIf { it >= 0 } ?: return null
            val move = instructions[writer] as? TwoRegisterInstruction ?: return null
            if (move.opcode !in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) ||
                move.registerA != current
            ) {
                return null
            }
            before = writer
            current = move.registerB
        }
        return null
    }

    private fun Instruction.calls(type: String, method: String): Boolean =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.definingClass == type && it.name == method } == true

    private fun Instruction.call(): String? =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
        }
}
