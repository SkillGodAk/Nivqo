package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val ORIGINAL_VIDEO = "original_video"
/**
 * Encrypted and regular chats hand every video to this transcoder's private worker. It reads the video, picks a target
 * bitrate and then decides between Messenger's own passthrough, which returns the original file, and a re-encode.
 */
internal const val VIDEO_TRANSCODE = "$MEDIA_TRANSCODER->A05(Lcom/facebook/msys/mci/TranscodeVideoCompletionCallback;" +
    "Lcom/facebook/msys/mci/VideoEdits;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V"
/** The performance annotation Messenger writes on its passthrough branch. */
internal const val VIDEO_PASSTHROUGH_MARK = "mci_video_passthrough"
private const val VIDEO_PASSTHROUGH_CALL = "$SETTINGS->videoPassthrough(IJ)I"

internal fun Method.isVideoTranscode(strings: Collection<String>) =
    definingClass == MEDIA_TRANSCODER && hookId() == VIDEO_TRANSCODE && !AccessFlags.STATIC.isSet(accessFlags) &&
        VIDEO_PASSTHROUGH_MARK in strings

private fun videoTranscodeChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the video passthrough check moved ($detail)")

private fun Instruction.wide() = (this as? WideLiteralInstruction)?.wideLiteral
private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference

/**
 * Index of the one size check that decides between Messenger's passthrough and a re-encode. 583 computes a limit from
 * the target bitrate and the duration, adds Messenger's configured margin in KB and keeps the original on less-than:
 *
 *     iget-wide vSize, vMeta, <metadata>->bytes:J
 *     ...                    vLimit = target bitrate
 *     int-to-long vLimit, vLimit
 *     iget-wide vT, vMeta, <metadata>->durationMs:J
 *     mul-long/2addr vLimit, vT
 *     const-wide/16 vT, 8000
 *     div-long/2addr vLimit, vT
 *     ...                    vT = margin
 *     mul-int/lit16 vT, vT, 1000
 *     int-to-long vT, vT
 *     add-long/2addr vLimit, vT
 *     cmp-long vR, vSize, vLimit     <- returned
 *     if-ltz vR, :keep_original
 *
 * A negative result keeps the original. The checks before it (trims, overlays, muting, the codec and Messenger's own
 * passthrough size cap) and the force-transcode option after it are left as they are. Messenger skips this check when
 * its margin is zero or less, and then this patch can't keep the original either.
 */
internal fun Method.videoPassthroughSite(): Int {
    val code = implementation?.instructions?.toList() ?: videoTranscodeChanged("no code")
    fun configuredMargin(c: Int) = code[c + 1].opcode == Opcode.IF_LTZ &&
        code[c - 6].opcode == Opcode.CONST_WIDE_16 && code[c - 6].wide() == 8000L &&
        code[c - 3].opcode == Opcode.MUL_INT_LIT16 && code[c - 3].wide() == 1000L
    val sites = (11 until code.size - 1).filter { c -> code[c].opcode == Opcode.CMP_LONG && configuredMargin(c) }
    val c = sites.singleOrNull() ?: videoTranscodeChanged("${sites.size} size checks")
    val cmp = code[c] as ThreeRegisterInstruction
    val limit = cmp.registerC
    val size = cmp.registerB
    val result = cmp.registerA
    fun twoReg(at: Int, op: Opcode) = (code[at] as? TwoRegisterInstruction)?.takeIf { code[at].opcode == op }
        ?: videoTranscodeChanged("${code[at].opcode.name} at the limit")
    val add = twoReg(c - 1, Opcode.ADD_LONG_2ADDR)
    val widenMargin = twoReg(c - 2, Opcode.INT_TO_LONG)
    val margin = twoReg(c - 3, Opcode.MUL_INT_LIT16)
    val div = twoReg(c - 5, Opcode.DIV_LONG_2ADDR)
    val mul = twoReg(c - 7, Opcode.MUL_LONG_2ADDR)
    val duration = twoReg(c - 8, Opcode.IGET_WIDE)
    val widen = twoReg(c - 9, Opcode.INT_TO_LONG)
    val bytes = twoReg(c - 11, Opcode.IGET_WIDE)
    val temp = (code[c - 6] as OneRegisterInstruction).registerA
    val marginInTemp = widenMargin.registerA == temp && widenMargin.registerB == temp && margin.registerA == temp && margin.registerB == temp
    if (add.registerA != limit || add.registerB != temp || !marginInTemp || div.registerA != limit || div.registerB != temp ||
        mul.registerA != limit || mul.registerB != duration.registerA || widen.registerA != limit || widen.registerB != limit) {
        videoTranscodeChanged("limit arithmetic")
    }
    val bytesField = code[c - 11].field() ?: videoTranscodeChanged("size field")
    val durationField = code[c - 8].field() ?: videoTranscodeChanged("duration field")
    if (bytes.registerA != size || bytesField.type != "J" || durationField.type != "J" ||
        bytesField.definingClass != durationField.definingClass || bytesField.name == durationField.name ||
        bytes.registerB != duration.registerB) videoTranscodeChanged("metadata reads")
    // Nothing between the size read and the compare may write the size pair.
    for (at in c - 10 until c) {
        val written = (code[at] as? OneRegisterInstruction)?.registerA ?: continue
        if (written == size || written == size + 1 || written + 1 == size) videoTranscodeChanged("size register reused")
    }
    if ((code[c + 1] as OneRegisterInstruction).registerA != result) videoTranscodeChanged("branch register")
    if (c + 1 in jumpTargets()) videoTranscodeChanged("branch is a jump target")
    // The helper call is a plain invoke, so the result and the size pair stay at v15 or below.
    if (result > 15 || size + 1 > 15 || result == size || result == size + 1) videoTranscodeChanged("registers out of range")
    return c
}

internal fun MutableMethod.validateOriginalVideo(): Int {
    if (AccessFlags.STATIC.isSet(accessFlags) || hookId() != VIDEO_TRANSCODE) {
        throw PatchException("Messenger controls: unexpected video transcoder ${hookId()}")
    }
    return videoPassthroughSite()
}

/**
 * Hands the compare result and the file size to the extension right after the compare. Off, Pause, safe mode and a
 * file over Messenger's 25 MB upload size get the stock result back, so only the re-encode decision changes.
 */
internal fun MutableMethod.injectOriginalVideo() {
    val c = validateOriginalVideo()
    val cmp = implementation!!.instructions.elementAt(c) as ThreeRegisterInstruction
    val result = cmp.registerA
    val size = cmp.registerB
    addInstructions(c + 1, """
        invoke-static {v$result, v$size, v${size + 1}}, $VIDEO_PASSTHROUGH_CALL
        move-result v$result
    """.trimIndent())
}
