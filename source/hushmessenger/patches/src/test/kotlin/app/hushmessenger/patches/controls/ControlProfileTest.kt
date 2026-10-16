package app.hushmessenger.patches.controls

import app.hushmessenger.patches.CompatMirror
import app.hushmessenger.patches.MessengerTarget
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
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
            assertEquals(ExpectedTotals.HOOKS_583, profile.hooks.values.sumOf { it.size })
        }
        assertEquals(9, BASE_PROFILE.hooks.getValue(EMOJI_DRAWER).size)
    }

    @Test fun theVersionCodePicksTheProfile() {
        assertEquals(MessengerTarget.VERSION_CODES.toSet(), controlProfiles.keys)
        assertSame(PROFILE_346611351, controlProfileFor("346611351"))
        for (code in listOf("346611369", "346611423", "346611438", "346611439", "346611440", null)) {
            assertSame(BASE_PROFILE, controlProfileFor(code))
        }
    }

    @Test fun aSecondVersionNameUsesItsOwnBuildsProfile() {
        val versions = MessengerTarget.VERSIONS + ("584.0.0.1.91" to listOf(347000001))
        val profiles = controlProfiles + (347000001 to PROFILE_346611351)
        assertSame(PROFILE_346611351, controlProfileFor("347000001", profiles))
        assertSame(BASE_PROFILE, controlProfileFor("346611369", profiles))
        val found = mapOf("ad_context_banner" to PROFILE_346611351.hooks.getValue("ad_context_banner").map { id ->
            fixtureMethod(id, "const/4 v0, 0x0\nreturn v0")
        })
        activeProfile = controlProfileFor("347000001", profiles)
        validateControls(found, setOf("ad_context_banner"), versions)
        activeProfile = controlProfileFor("346611369", profiles)
        val failure = assertFailsWith<PatchException> { validateControls(found, setOf("ad_context_banner"), versions) }
        assertContains(failure.message.orEmpty(),
            "Use an unmodified arm64 Messenger ${MessengerTarget.supportedApks()} or 584.0.0.1.91 APK (version code 347000001).")
    }

    @Test fun validationFollowsTheActiveBuild() {
        fun found(ids: Set<String>) = mapOf("ad_context_banner" to ids.map { id ->
            fixtureMethod(id, "const/4 v0, 0x0\nreturn v0")
        })
        activeProfile = BASE_PROFILE
        val base = found(BASE_PROFILE.hooks.getValue("ad_context_banner"))
        val other = found(PROFILE_346611351.hooks.getValue("ad_context_banner"))
        validateControls(base, setOf("ad_context_banner"))
        assertFailsWith<PatchException> { validateControls(other, setOf("ad_context_banner")) }
        activeProfile = PROFILE_346611351
        validateControls(other, setOf("ad_context_banner"))
        assertFailsWith<PatchException> { validateControls(base, setOf("ad_context_banner")) }
    }

    private val builder = "Lcom/google/common/collect/ImmutableList${'$'}Builder;"
    private val copy = "$builder->addAll(Ljava/lang/Iterable;)$builder"
    private val build = "LX/34B;->A01($builder)Lcom/google/common/collect/ImmutableList;"
    private val folded = "LX/34B;->A0l(${builder}Ljava/lang/Iterable;)Lcom/google/common/collect/ImmutableList;"

    // Shaped like 583's BaseXappComposerConfigurationFactory.A6l, where one call takes the builder and the local list.
    // [addAllCopy] is 582's form instead: the builder's addAll, then a build.
    private fun inlineTabs(
        extraCopy: Boolean = false,
        branchIntoCopy: Boolean = false,
        switchIntoCopy: Boolean = false,
        catchIntoCopy: Boolean = false,
        addAllCopy: Boolean = false,
        rangeCopy: Boolean = false,
    ) = fixtureMethod("$COMPOSER_FACTORY->A6X(LX/5vA;)V", """
            new-instance v12, Ljava/util/ArrayList;
            invoke-direct {v12}, Ljava/util/ArrayList;-><init>()V
            invoke-static {}, Lcom/google/common/collect/ImmutableList;->builder()$builder
            move-result-object v2
            ${if (branchIntoCopy) "if-eqz v2, :copy" else "nop"}
            const/4 v4, 0x0
            ${if (switchIntoCopy) "packed-switch v4, :cases" else "nop"}
            :copy
            ${when {
                addAllCopy -> "invoke-virtual {v2, v12}, $copy\ninvoke-static {v2}, $build"
                rangeCopy -> "move-object/from16 v3, v12\ninvoke-static/range {v2 .. v3}, $folded"
                else -> "invoke-static {v2, v12}, $folded"
            }}
            move-result-object v3
            ${if (extraCopy) "invoke-static {v2, v12}, $folded" else "nop"}
            sget-object v0, $AVATAR_TAB_EVENT->A00:$AVATAR_TAB_EVENT
            return-void
            ${if (switchIntoCopy) ":cases\n.packed-switch 0x1\n:copy\n.end packed-switch" else ""}
        """.trimIndent(), registers = 16).apply {
            // Instruction snippets carry no try blocks, so the handler is added directly.
            if (catchIntoCopy) implementation!!.run {
                val copyIndex = instructions.indexOfFirst { (it as? ReferenceInstruction)?.reference.toString() == folded }
                addCatch(newLabelForIndex(2), newLabelForIndex(4), newLabelForIndex(copyIndex))
            }
        }

    @Test fun anInlineTabListIsFilteredJustBeforeItsOneCallCopiesAndBuildsIt() {
        val method = inlineTabs()
        val copyIndex = method.implementation!!.instructions.indexOfFirst {
            (it as? ReferenceInstruction)?.reference.toString() == folded
        }
        method.injectKeyboardTabsInline()
        val call = method.getInstruction(copyIndex)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals("$SETTINGS->removeAvatarTabs(Ljava/lang/Iterable;)V", (call as ReferenceInstruction).reference.toString())
        // The hook gets the Iterable (v12), not the Builder (v2).
        val range = call as com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
        assertEquals(12, range.startRegister)
        assertEquals(1, range.registerCount)
        assertEquals(folded, (method.getInstruction(copyIndex + 1) as ReferenceInstruction).reference.toString())
    }

    @Test fun anInlineTabListWithTwoCopiesOrAJumpIntoTheCopyIsRefused() {
        assertFailsWith<PatchException> { inlineTabs(extraCopy = true).injectKeyboardTabsInline() }
        assertFailsWith<PatchException> { inlineTabs(branchIntoCopy = true).injectKeyboardTabsInline() }
        assertFailsWith<PatchException> { inlineTabs(switchIntoCopy = true).injectKeyboardTabsInline() }
        assertFailsWith<PatchException> { inlineTabs(catchIntoCopy = true).injectKeyboardTabsInline() }
    }

    @Test fun a582TabListCopiedThroughTheBuildersAddAllIsRefused() {
        assertFailsWith<PatchException> { inlineTabs(addAllCopy = true).injectKeyboardTabsInline() }
    }

    @Test fun compatReportTakesTheTabListOnlyWhereThePatchCanFilterIt() {
        // The patch finds the method, then validates it just before its edit; CompatReport does both at discovery.
        fun takenBy(method: com.android.tools.smali.dexlib2.iface.Method): Pair<Boolean, Boolean> {
            val classes = listOf(fixtureClass(COMPOSER_FACTORY, listOf(method)))
            val found = mirroredControls(classes)["avatar_tabs"].orEmpty()
            val patch = found.size == 1 && runCatching { (found.single() as MutableMethod).validateKeyboardTabsInline() }.isSuccess
            return patch to CompatMirror.controls(classes).containsKey("avatar_tabs")
        }
        assertEquals(true to true, takenBy(inlineTabs()))
        for ((name, refused) in mapOf(
            "two copies" to inlineTabs(extraCopy = true), "a branch into the copy" to inlineTabs(branchIntoCopy = true),
            "a switch case into the copy" to inlineTabs(switchIntoCopy = true), "a catch into the copy" to inlineTabs(catchIntoCopy = true),
            "582's addAll" to inlineTabs(addAllCopy = true), "the range form" to inlineTabs(rangeCopy = true),
        )) assertEquals(false to false, takenBy(refused), name)
    }
}
