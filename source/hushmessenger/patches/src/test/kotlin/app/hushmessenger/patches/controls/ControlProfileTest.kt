package app.hushmessenger.patches.controls

import app.hushmessenger.patches.MessengerTarget
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ControlProfileTest {
    @AfterTest fun reset() {
        activeProfile = SYNTHETIC_PROFILE
    }

    @Test fun eachBuildListsTheSameControlsWithTheSameNumberOfHooks() {
        for (profile in controlProfiles.values) {
            assertEquals(BASE_PROFILE.hooks.keys, profile.hooks.keys)
            for (key in BASE_PROFILE.hooks.keys) {
                assertEquals(BASE_PROFILE.hooks.getValue(key).size, profile.hooks.getValue(key).size, key)
            }
            assertEquals(ExpectedTotals.HOOKS_582, profile.hooks.values.sumOf { it.size })
        }
        assertEquals(9, BASE_PROFILE.hooks.getValue(EMOJI_DRAWER).size)
    }

    @Test fun theVersionCodePicksTheProfile() {
        assertEquals(MessengerTarget.VERSION_CODES.toSet(), controlProfiles.keys)
        assertSame(PROFILE_346415706, controlProfileFor("346415706"))
        assertSame(PROFILE_346415706, controlProfileFor("346415707"))
        for (code in listOf("346415686", "346415687", "346415690", "346415720", "346415777", null)) {
            assertSame(BASE_PROFILE, controlProfileFor(code))
        }
    }

    @Test fun aSecondVersionNameUsesItsOwnBuildsProfile() {
        val versions = MessengerTarget.VERSIONS + ("583.0.0.1.91" to listOf(347000001))
        val profiles = controlProfiles + (347000001 to PROFILE_346415706)
        assertSame(PROFILE_346415706, controlProfileFor("347000001", profiles))
        assertSame(BASE_PROFILE, controlProfileFor("346415686", profiles))
        val found = mapOf("people" to PROFILE_346415706.hooks.getValue("people").map { id ->
            fixtureMethod(id, "const/4 v0, 0x0\nreturn v0")
        })
        activeProfile = controlProfileFor("347000001", profiles)
        validateControls(found, setOf("people"), versions)
        activeProfile = controlProfileFor("346415686", profiles)
        val failure = assertFailsWith<PatchException> { validateControls(found, setOf("people"), versions) }
        assertContains(failure.message.orEmpty(),
            "Use an unmodified arm64 Messenger ${MessengerTarget.supportedApks()} or 583.0.0.1.91 APK (version code 347000001).")
    }

    @Test fun validationFollowsTheActiveBuild() {
        fun found(ids: Set<String>) = mapOf("people" to ids.map { id ->
            fixtureMethod(id, "const/4 v0, 0x0\nreturn v0")
        })
        activeProfile = BASE_PROFILE
        val base = found(BASE_PROFILE.hooks.getValue("people"))
        val other = found(PROFILE_346415706.hooks.getValue("people"))
        validateControls(base, setOf("people"))
        assertFailsWith<PatchException> { validateControls(other, setOf("people")) }
        activeProfile = PROFILE_346415706
        validateControls(other, setOf("people"))
        assertFailsWith<PatchException> { validateControls(base, setOf("people")) }
    }

    private val builder = "Lcom/google/common/collect/ImmutableList${'$'}Builder;"
    private val copy = "$builder->addAll(Ljava/lang/Iterable;)$builder"
    private val build = "LX/34B;->A01($builder)Lcom/google/common/collect/ImmutableList;"

    // Shaped like 582's BaseXappComposerConfigurationFactory.A6X: addAll the local list, then build.
    private fun inlineTabs(
        extraCopy: Boolean = false,
        branchIntoCopy: Boolean = false,
        switchIntoCopy: Boolean = false,
        catchIntoCopy: Boolean = false,
        buildsAnotherBuilder: Boolean = false,
        noBuild: Boolean = false,
    ) = fixtureMethod("$COMPOSER_FACTORY->A6X(LX/5vA;)V", """
            new-instance v12, Ljava/util/ArrayList;
            invoke-direct {v12}, Ljava/util/ArrayList;-><init>()V
            invoke-static {}, Lcom/google/common/collect/ImmutableList;->builder()$builder
            move-result-object v2
            ${if (branchIntoCopy) "if-eqz v2, :copy" else "nop"}
            const/4 v4, 0x0
            ${if (switchIntoCopy) "packed-switch v4, :cases" else "nop"}
            :copy
            invoke-virtual {v2, v12}, $copy
            ${if (noBuild) "nop" else "invoke-static {${if (buildsAnotherBuilder) "v5" else "v2"}}, $build"}
            move-result-object v3
            ${if (extraCopy) "invoke-virtual {v2, v12}, $copy" else "nop"}
            return-void
            ${if (switchIntoCopy) ":cases\n.packed-switch 0x1\n:copy\n.end packed-switch" else ""}
        """.trimIndent(), registers = 16).apply {
            // Instruction snippets carry no try blocks, so the handler is added directly.
            if (catchIntoCopy) implementation!!.run {
                val copyIndex = instructions.indexOfFirst { (it as? ReferenceInstruction)?.reference.toString() == copy }
                addCatch(newLabelForIndex(2), newLabelForIndex(4), newLabelForIndex(copyIndex))
            }
        }

    @Test fun anInlineTabListIsFilteredJustBeforeItIsCopied() {
        val method = inlineTabs()
        val copyIndex = method.implementation!!.instructions.indexOfFirst {
            (it as? ReferenceInstruction)?.reference.toString() == copy
        }
        method.injectKeyboardTabsInline()
        val call = method.getInstruction(copyIndex)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals("$SETTINGS->removeAvatarTabs(Ljava/lang/Iterable;)V", (call as ReferenceInstruction).reference.toString())
        // The hook gets the Iterable (v12), not the Builder (v2).
        val range = call as com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
        assertEquals(12, range.startRegister)
        assertEquals(1, range.registerCount)
        assertEquals(copy, (method.getInstruction(copyIndex + 1) as ReferenceInstruction).reference.toString())
        assertEquals(build, (method.getInstruction(copyIndex + 2) as ReferenceInstruction).reference.toString())
    }

    @Test fun anInlineTabListWithTwoCopiesOrABranchIntoTheCopyIsRefused() {
        assertFailsWith<PatchException> { inlineTabs(extraCopy = true).injectKeyboardTabsInline() }
        assertFailsWith<PatchException> { inlineTabs(branchIntoCopy = true).injectKeyboardTabsInline() }
        assertFailsWith<PatchException> { inlineTabs(switchIntoCopy = true).injectKeyboardTabsInline() }
        assertFailsWith<PatchException> { inlineTabs(catchIntoCopy = true).injectKeyboardTabsInline() }
        assertFailsWith<PatchException> { inlineTabs(buildsAnotherBuilder = true).injectKeyboardTabsInline() }
        assertFailsWith<PatchException> { inlineTabs(noBuild = true).injectKeyboardTabsInline() }
    }
}
