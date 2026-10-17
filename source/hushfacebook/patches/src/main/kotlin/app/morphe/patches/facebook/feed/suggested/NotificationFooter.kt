/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * The "More from your friends" list under a post opened from a notification (#163). Read from 582,
 * 2026-10-15; the obfuscated names are for reviewers, the code never writes one down.
 *
 * A post opened from Notifications is fetched as a FetchSingleStoryParams with a notification source,
 * and the query builder (arm64 LX/D29;->A03) sets the parameter below to a MobileConfig answer, or to
 * false with no source. It does so three times, once per query variant: twice boxed through
 * Boolean.valueOf right before the name, once handed over raw after a jump. With it true the server
 * adds a Bloks footer of more posts, ads among them, that never passes the feed's edge filter.
 * Answering false asks for none.
 */

/** The query parameter asking the server for the footer of more posts. */
internal const val NOTIFICATION_FOOTER_FLAG = "show_notification_permalink_footer"

internal const val NOTIFICATION_FOOTER =
    "$EXTENSION_PACKAGE/feed/NotificationFooter;->show(Z)Z"

private const val BOOLEAN_VALUE_OF = "Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;"

private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

/**
 * The indexes of the `move-result` instructions holding the boolean [method] hands over as
 * [NOTIFICATION_FOOTER_FLAG], in order: each comes right after a call answering a boolean and is
 * followed by the name, either directly or through `Boolean.valueOf` and its `move-result-object`.
 * Null unless the method holds the name and every one is in that shape.
 */
internal fun footerFlagAnswers(method: Method): List<Int>? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val names = code.indices.filter { index ->
        ((code[index] as? ReferenceInstruction)?.reference as? StringReference)?.string == NOTIFICATION_FOOTER_FLAG
    }
    if (names.isEmpty()) return null
    return names.map { name ->
        val boxed = name >= 2 && code[name - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            code[name - 2].called()?.toString() == BOOLEAN_VALUE_OF
        val answer = if (boxed) name - 3 else name - 1
        if (answer < 1 || code[answer].opcode != Opcode.MOVE_RESULT) return null
        if (code[answer - 1].called()?.returnType != "Z") return null
        answer
    }
}

/** The methods outside the extension holding [NOTIFICATION_FOOTER_FLAG]. */
internal fun BytecodePatchContext.footerFlagHolders(): List<Method> =
    classDefByStrings(NOTIFICATION_FOOTER_FLAG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .flatMap { methodsHolding(it, NOTIFICATION_FOOTER_FLAG) }

/**
 * Hands each of Facebook's answers for [NOTIFICATION_FOOTER_FLAG] to the extension as it comes back,
 * in its own register. The paths with no notification source jump past the hook with false, which
 * needs nothing. Refuses unless one method sets the flag, every time in that shape.
 */
internal fun BytecodePatchContext.hookNotificationFooter() {
    val holders = footerFlagHolders()
    val method = holders.singleOrNull()
        ?: throw PatchException("expected one method setting \"$NOTIFICATION_FOOTER_FLAG\", found ${holders.size}")
    val answers = footerFlagAnswers(method)
        ?: throw PatchException("${method.definingClass}->${method.name} doesn't set \"$NOTIFICATION_FOOTER_FLAG\" from a boolean answer")
    val code = method.implementation!!.instructions.toList()
    val mutable = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
    // From the last one back, so the earlier indexes still hold.
    for (answer in answers.reversed()) {
        val register = (code[answer] as OneRegisterInstruction).registerA
        mutable.addInstructions(
            answer + 1,
            """
                invoke-static/range { v$register .. v$register }, $NOTIFICATION_FOOTER
                move-result v$register
            """,
        )
    }
}
