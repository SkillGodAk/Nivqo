package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal const val SYNTHETIC_SHARE_COPY = "LX/KLZ;->call()Ljava/lang/Object;"

private const val OPEN = "invoke-direct {v3, v8, v1}, Ljava/io/FileOutputStream;-><init>(Ljava/io/File;Z)V"
private const val COPY = "invoke-static {v2, v3}, LX/8Bb;->A00(Ljava/io/InputStream;Ljava/io/OutputStream;)V"
private const val CLOSE = "invoke-virtual {v3}, Ljava/io/OutputStream;->close()V"
private const val FROM_FILE = "invoke-static {v8}, Landroid/net/Uri;->fromFile(Ljava/io/File;)Landroid/net/Uri;"

/** The share copy's end as 582 writes it: open the copy on the File, copy the stream in, close it, point a Uri at it. */
private fun shareCopy(
    name: String = SHARE_MEDIA_NAME, copy: String = COPY, close: String = CLOSE, fromFile: String = FROM_FILE,
    jump: String = "", id: String = SYNTHETIC_SHARE_COPY,
) = fixtureMethod(id, """
    $jump
    const-string v1, "$name"
    new-instance v3, Ljava/io/FileOutputStream;
    const/4 v1, 0x0
    $OPEN
    $copy
    $close
    :after_close
    $fromFile
    move-result-object v1
    return-object v1
""".trimIndent(), registers = 12)

/** The share copy for the catalog-wide discovery fixture. */
internal fun shareCopyFixture() = listOf(fixtureClass("LX/KLZ;", listOf(shareCopy())))

private fun Method.code() = implementation!!.instructions.toList()

/**
 * The helper sits right before Uri.fromFile and names the copy's File, and nothing stock moves or changes. An odd-length
 * insertion can add or drop the nop that aligns a switch table further down, so nops don't count.
 */
private fun assertCopyHook(before: List<Instruction>, after: List<Instruction>, site: Int, label: String) {
    val file = (before[site] as FiveRegisterInstruction).registerC
    val helper = after[site] as RegisterRangeInstruction
    assertEquals(Opcode.INVOKE_STATIC_RANGE, helper.opcode, label)
    assertEquals(XHE_AUDIO_HELPER, (helper as ReferenceInstruction).reference.toString(), label)
    assertEquals(listOf(1, file), listOf(helper.registerCount, helper.startRegister), label)
    assertSame(before[site], after[site + 1], label)
    assertStockKept(before.filter { it.opcode != Opcode.NOP }, after.filterIndexed { i, it -> i != site && it.opcode != Opcode.NOP }, label)
}

class XheAudioTest {
    @AfterTest fun reset() { activeProfile = SYNTHETIC_PROFILE }

    @Test fun theFinishedCopyGoesThroughTheExtensionBeforeMessengerReadsIt() {
        val method = shareCopy()
        val found = findControls(shareCopyFixture())
        assertEquals(listOf(SYNTHETIC_SHARE_COPY), found.getValue(XHE_AUDIO).map { it.hookId() })
        validateControls(found, setOf(XHE_AUDIO))
        val before = method.code()
        val site = method.shareCopySite()!!
        injectControl(XHE_AUDIO, mapOf(XHE_AUDIO to listOf(method)))
        assertCopyHook(before, method.code(), site, "copy")
        // Two copies that look alike are ambiguous, so neither is hooked.
        val twin = fixtureClass("LX/KLY;", listOf(shareCopy(id = SYNTHETIC_SHARE_COPY.replace("LX/KLZ;", "LX/KLY;"))))
        assertTrue(findXheAudio(shareCopyFixture() + twin).isEmpty())
    }

    @Test fun aCopyThatMovedIsntFoundOrStopsBeforeAnyEdit() {
        // Discovery skips a method whose copy doesn't end on the same File and stream it opened.
        val moved = mapOf(
            "closes another stream" to shareCopy(close = "invoke-virtual {v4}, Ljava/io/OutputStream;->close()V"),
            "points the Uri at another file" to shareCopy(fromFile = "invoke-static {v9}, Landroid/net/Uri;->fromFile(Ljava/io/File;)Landroid/net/Uri;"),
            "copies something else" to shareCopy(copy = "invoke-static {v2}, LX/8Bb;->A00(Ljava/io/InputStream;)V"),
            "copy writes to another stream" to shareCopy(copy = "invoke-static {v2, v4}, LX/8Bb;->A00(Ljava/io/InputStream;Ljava/io/OutputStream;)V"),
        )
        for ((case, method) in moved) assertNull(method.shareCopySite(), case)
        assertTrue(findXheAudio(listOf(fixtureClass("LX/KLZ;", listOf(shareCopy(name = "orca_other_media"))))).isEmpty(), "another name")
        // A jump straight to the Uri would hand Messenger the copy without the extension seeing it.
        val jumped = shareCopy(jump = "if-eqz v3, :after_close")
        val before = jumped.code()
        val failure = assertFailsWith<PatchException> { injectControl(XHE_AUDIO, mapOf(XHE_AUDIO to listOf(jumped))) }
        assertContains(failure.message.orEmpty(), "shared video copy moved")
        assertEquals(before, jumped.code())
    }

    @Test fun everySupportedBuildHooksItsOneShareCopy() {
        val root = System.getenv("HUSH_NATIVE_FIXTURES")
        assumeTrue(root != null, "Set HUSH_NATIVE_FIXTURES to the exact stock fixture directory")
        val apks = Files.list(Path.of(root!!)).use { it.filter { p -> p.toString().endsWith(".apk") }.sorted().toList() }
        assertEquals(controlProfiles.size, apks.size)
        for (apk in apks) {
            val code = apk.fileName.toString().substringBeforeLast(".apk").substringAfterLast('-')
            activeProfile = controlProfileFor(code)
            val dex = DexFileFactory.loadDexContainer(apk.toFile(), Opcodes.forApi(35))
            val copies = findXheAudio(dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes })
            assertEquals(activeProfile.hooks.getValue(XHE_AUDIO), copies.map { it.hookId() }.toSet(), code)
            validateControls(mapOf(XHE_AUDIO to copies), setOf(XHE_AUDIO))
            val method = MutableMethod(copies.single())
            val before = method.code()
            val site = method.shareCopySite()!!
            method.injectXheAudio()
            assertCopyHook(before, method.code(), site, code)
        }
    }
}
