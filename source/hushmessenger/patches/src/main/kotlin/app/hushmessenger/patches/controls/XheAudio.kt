package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** Videos shared into Messenger whose only sound is xHE-AAC, which Messenger can't send. */
internal const val XHE_AUDIO = "xhe_audio"

/** The name Messenger gives the copy of an item shared into it. Only the share copy uses it. */
internal const val SHARE_MEDIA_NAME = "orca_share_media"

private const val FILE = "Ljava/io/File;"
private const val OPEN_COPY = "Ljava/io/FileOutputStream;-><init>(${FILE}Z)V"
private const val CLOSE_COPY = "Ljava/io/OutputStream;->close()V"
private const val FROM_FILE = "Landroid/net/Uri;->fromFile($FILE)Landroid/net/Uri;"
internal const val XHE_AUDIO_HELPER = "Lapp/hushmessenger/extension/XheAudio;->fixSharedFile($FILE)V"

private fun xheChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the shared video copy moved ($detail)")

private fun ReferenceInstruction.ref() = reference.toString()

/**
 * Index of the `Uri.fromFile` that points the new attachment at Messenger's finished copy of a shared item. The copy
 * ends like this:
 *
 *     invoke-direct {vOut, vFile, vAppend}, FileOutputStream-><init>(File, Z)V
 *     invoke-static {vIn, vOut}, <copy>(InputStream, OutputStream)V
 *     invoke-virtual {vOut}, OutputStream->close()V
 *     invoke-static {vFile}, Uri->fromFile(File)Uri             <- the helper goes in before this
 *
 * Null when the method has no such site, or more than one.
 */
internal fun Method.shareCopySite(): Int? {
    val code = implementation?.instructions?.toList() ?: return null
    val sites = (3 until code.size).filter { at ->
        val point = code[at] as? FiveRegisterInstruction
        val close = code[at - 1] as? FiveRegisterInstruction
        val copy = code[at - 2] as? FiveRegisterInstruction
        val open = code[at - 3] as? FiveRegisterInstruction
        val copier = (copy as? ReferenceInstruction)?.reference as? MethodReference
        point != null && close != null && copy != null && open != null && copier != null &&
            point.opcode == Opcode.INVOKE_STATIC && (point as ReferenceInstruction).ref() == FROM_FILE &&
            close.opcode == Opcode.INVOKE_VIRTUAL && (close as ReferenceInstruction).ref() == CLOSE_COPY &&
            copy.opcode == Opcode.INVOKE_STATIC && copier.returnType == "V" &&
            copier.parameterTypes.map { it.toString() } == listOf("Ljava/io/InputStream;", "Ljava/io/OutputStream;") &&
            open.opcode == Opcode.INVOKE_DIRECT && (open as ReferenceInstruction).ref() == OPEN_COPY &&
            open.registerD == point.registerC && open.registerC == close.registerC && copy.registerD == close.registerC
    }
    return sites.singleOrNull()
}

/** A no-argument `call()` that names its copies orca_share_media and ends one copy as [shareCopySite] reads it. */
internal fun Method.isShareCopy(): Boolean =
    name == "call" && parameterTypes.isEmpty() && returnType == "Ljava/lang/Object;" &&
        implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == SHARE_MEDIA_NAME } == true &&
        shareCopySite() != null

/** The one share copy, or none when more than one method looks like it. */
internal fun findXheAudio(classes: Iterable<ClassDef>): List<Method> =
    classes.flatMap { it.methods }.filter { it.isShareCopy() }.let { if (it.size == 1) it else emptyList() }

internal fun MutableMethod.validateXheAudio(): Int {
    if (!isShareCopy()) xheChanged("unexpected copy ${hookId()}")
    val site = shareCopySite()!!
    // Only the close may reach the finished copy, or a path could hand Messenger the file unchecked.
    if (site in jumpTargets()) xheChanged("a jump past the closed copy")
    return site
}

/**
 * Hands the extension the finished copy right after Messenger closes it, before anything reads it. With the switch
 * on, a video whose only sound is xHE-AAC gets that sound re-encoded to AAC-LC in the same file. Messenger reads the
 * file's size after this point, and its type and length don't change, so the attachment stays right. The helper
 * catches its own failures, so the share goes on with the copy as it was.
 */
internal fun MutableMethod.injectXheAudio() {
    val site = validateXheAudio()
    val file = (implementation!!.instructions.elementAt(site) as FiveRegisterInstruction).registerC
    addInstructions(site, "invoke-static/range {v$file .. v$file}, $XHE_AUDIO_HELPER")
}
