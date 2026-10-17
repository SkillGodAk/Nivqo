/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The time on Facebook's older comment row, left of Like and Reply (#138). Read from 582, 2026-10-14;
 * the obfuscated names here are for reviewers, the code never writes one down.
 *
 * The row (CommentActionsWithReactionsMetadataComponent) builds its items from the commentufi
 * plugins, and the time is TimestampPlugin, a class Redex keeps. Its one static method answering a
 * SpannableString formats the time, reads the span's colour from a static (Context, int, int) int
 * helper (582 arm64 LX/2lR;->A00: Theme.resolveAttribute of attribute 0x7f04028a, Facebook's styles
 * point it at the secondary text attribute, with SECONDARY_TEXT's FDSColors answer as the fallback)
 * and puts it on one span over the whole time. On a black AMOLED sheet the slot came out blank.
 */

internal const val COMMENT_TIME_PLUGIN = "Lcom/facebook/feedback/comments/plugins/commentufi/timestamp/TimestampPlugin;"

internal const val COMMENT_TIME = "Lapp/morphe/extension/facebook/theme/CommentTimeColour;->commentTime(I)I"

private const val SPANNABLE_STRING = "Landroid/text/SpannableString;"

private val THEME_COLOUR_READ = listOf("Landroid/content/Context;", "I", "I")

/** The plugin's method that builds the time's text: its one static method answering a SpannableString. */
internal fun commentTimeBuilder(methods: Iterable<Method>): Method {
    val found = methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == SPANNABLE_STRING && it.implementation != null
    }
    return found.singleOrNull()
        ?: throw PatchException("$COMMENT_TIME_PLUGIN has ${found.size} static methods building a SpannableString, expected one")
}

/**
 * The index of the move-result of each static (Context, int, int) int call in [method], the read of
 * the span's colour from the theme. The extension's own call is no such read.
 */
internal fun commentTimeColourReads(method: Method): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
        code[at].opcode == Opcode.INVOKE_STATIC && call.returnType == "I" &&
            call.parameterTypes.map(CharSequence::toString) == THEME_COLOUR_READ &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT
    }.map { it + 1 }
}

/** Whether [method] already hands the time's colour to the extension. */
private fun handsTimeColour(method: Method): Boolean =
    method.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference)?.toString() == COMMENT_TIME } == true

/**
 * Hands the colour TimestampPlugin reads for the older comment row's time to the extension, which
 * gives back one that reads on the page in dark mode with AMOLED or Material You in the build.
 * AMOLED and Material You both call this; the second call finds the hook in place and leaves it, and
 * the extension decides at runtime which themes are in. Refuses unless the plugin has one such read.
 */
internal fun BytecodePatchContext.hookCommentTimeColour() {
    val plugin = mutableClassDefByOrNull(COMMENT_TIME_PLUGIN)
        ?: throw PatchException("This Facebook build has no $COMMENT_TIME_PLUGIN")
    val builder = commentTimeBuilder(plugin.methods)
    val parameters = builder.parameterTypes.map(CharSequence::toString)
    val method = plugin.methods.single { it.name == builder.name && it.parameterTypes.map(CharSequence::toString) == parameters }
    if (handsTimeColour(method)) return
    method.recolourCommentTime()
}

/** [hookCommentTimeColour] for the builder itself: the extension right after the one read, on its register. */
internal fun MutableMethod.recolourCommentTime() {
    val reads = commentTimeColourReads(this)
    if (reads.size != 1) {
        throw PatchException("$definingClass->$name reads ${reads.size} theme colours, expected the time's one")
    }
    val moveResult = reads.single()
    val register = (implementation!!.instructions[moveResult] as OneRegisterInstruction).registerA
    addInstructions(
        moveResult + 1,
        """
            invoke-static/range { v$register .. v$register }, $COMMENT_TIME
            move-result v$register
        """,
    )
}
