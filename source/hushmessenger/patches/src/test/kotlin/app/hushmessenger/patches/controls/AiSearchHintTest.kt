package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val SYNTHETIC_SEARCH_BAR = "LX/28Z;->A00(LX/1SO;LX/1vn;Lcom/facebook/mig/scheme/interfaces/MigColorScheme;Z)LX/1GG;"

private const val CHECK = "invoke-virtual {v4, v9}, LX/2Sx;->A09(Lcom/facebook/auth/usersession/FbUserSession;)Z"

/** The search bar builder as Messenger writes it: the Meta AI check, then the plain hint unless the check passed. */
private fun searchBar(check: String = CHECK, branch: String = "if-eqz v8, :pick", jump: String = "", id: String = SYNTHETIC_SEARCH_BAR) =
    fixtureMethod(id, """
        $jump
        $check
        move-result v8
        :after_check
        invoke-virtual {v11}, LX/1SO;->A03()Landroid/content/res/Resources;
        move-result-object v4
        const v1, 0x7f1300a8
        $branch
        const v1, 0x7f1301b2
        :pick
        invoke-virtual {v4, v1}, Landroid/content/res/Resources;->getString(I)Ljava/lang/String;
        move-result-object v2
        const-string v1, "$SEARCH_BAR_TAG"
        const/4 v0, 0x0
        return-object v0
    """.trimIndent(), registers = 20)

/** The builder for the catalog-wide discovery fixture. */
internal fun searchBarFixture() = listOf(fixtureClass("LX/28Z;", listOf(searchBar())))

private fun Method.code() = implementation!!.instructions.toList()

/** The helper pair sits right after the check's result, and nothing stock moves or changes. */
private fun assertHintHook(before: List<Instruction>, after: List<Instruction>, result: Int, label: String) {
    val held = (before[result] as OneRegisterInstruction).registerA
    assertEquals(before.size + 2, after.size, label)
    assertStockKept(before, after.filterIndexed { i, _ -> i != result + 1 && i != result + 2 }, label)
    val helper = after[result + 1] as RegisterRangeInstruction
    assertEquals(Opcode.INVOKE_STATIC_RANGE, helper.opcode, label)
    assertEquals(AI_SEARCH_HINT_HELPER, (helper as ReferenceInstruction).reference.toString(), label)
    assertEquals(listOf(1, held), listOf(helper.registerCount, helper.startRegister), label)
    assertEquals(Opcode.MOVE_RESULT, after[result + 2].opcode, label)
    assertEquals(held, (after[result + 2] as OneRegisterInstruction).registerA, label)
}

class AiSearchHintTest {
    @AfterTest fun reset() { activeProfile = SYNTHETIC_PROFILE }

    @Test fun theMetaAiCheckGoesThroughTheExtensionBeforeTheHintIsPicked() {
        val method = searchBar()
        val found = mirroredControls(searchBarFixture())
        assertEquals(listOf(SYNTHETIC_SEARCH_BAR), found.getValue(AI_SEARCH_HINT).map { it.hookId() })
        validateControls(found, setOf(AI_SEARCH_HINT))
        val before = method.code()
        val result = method.searchHintSite()!!
        injectControl(AI_SEARCH_HINT, mapOf(AI_SEARCH_HINT to listOf(method)))
        assertHintHook(before, method.code(), result, "builder")
        // Two builders that look alike are ambiguous, so neither is hooked.
        val twin = fixtureClass("LX/28Y;", listOf(searchBar(id = SYNTHETIC_SEARCH_BAR.replace("LX/28Z;", "LX/28Y;"))))
        assertTrue(findAiSearchHint(searchBarFixture() + twin).isEmpty())
    }

    @Test fun aShapeThatMovedIsntFoundOrStopsBeforeAnyEdit() {
        // Discovery skips a builder whose hint isn't picked by a session check right before getString.
        val moved = mapOf(
            "check takes no session" to searchBar(check = "invoke-virtual {v4}, LX/2Sx;->A09()Z"),
            "check answers a number" to searchBar(check = CHECK.replace(")Z", ")I")),
            "plain hint on not-equal" to searchBar(branch = "if-nez v8, :pick"),
            "branch tests another register" to searchBar(branch = "if-eqz v2, :pick"),
        )
        for ((case, method) in moved) assertNull(method.searchHintSite(), case)
        // A jump straight past the check's result would skip the extension.
        val jumped = searchBar(jump = "if-eqz v3, :after_check")
        val before = jumped.code()
        val failure = assertFailsWith<PatchException> { injectControl(AI_SEARCH_HINT, mapOf(AI_SEARCH_HINT to listOf(jumped))) }
        assertContains(failure.message.orEmpty(), "inbox search hint moved")
        assertEquals(before, jumped.code())
    }

    @Test fun everySupportedBuildHooksItsOneSearchBar() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString().substringBeforeLast(".apk").substringAfterLast('-')
            activeProfile = controlProfileFor(code)
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val builders = findAiSearchHint(dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes })
            assertEquals(activeProfile.hooks.getValue(AI_SEARCH_HINT), builders.map { it.hookId() }.toSet(), code)
            validateControls(mapOf(AI_SEARCH_HINT to builders), setOf(AI_SEARCH_HINT))
            val method = MutableMethod(builders.single())
            val before = method.code()
            val result = method.searchHintSite()!!
            method.injectAiSearchHint()
            assertHintHook(before, method.code(), result, code)
        }
    }
}
