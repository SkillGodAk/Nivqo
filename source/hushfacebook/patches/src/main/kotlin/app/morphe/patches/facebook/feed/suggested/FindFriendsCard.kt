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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * The "Want to see more posts from friends? Find friends" card where the feed runs out of friends'
 * posts (#187). Read from 583, 2026-10-19; the obfuscated names are for reviewers, the code never
 * writes one down.
 *
 * The end of the feed is one section (arm64 LX/2s6) whose children builder (A2b) walks a list of parts
 * and adds one for each: a spinner, the offline note, the suggestions upsell and this card, which
 * names itself "EndOfFeedCIUnitSection" (a contact importer unit). For the card it reads a string off
 * the section's settings (LX/2s5;->A0B), and only when that string isn't null does it build the card
 * and hand it the string. Some feed types skip the card the same way, jumping past it with nothing
 * added. Answering null skips it too.
 */

/** The name the friends card's section gives itself. */
internal const val FRIENDS_CARD_SECTION = "EndOfFeedCIUnitSection"

internal const val FRIENDS_CARD =
    "$EXTENSION_PACKAGE/feed/FindFriendsCard;->unit(Ljava/lang/String;)Ljava/lang/String;"

private const val STRING = "Ljava/lang/String;"

/**
 * The index of the `iget-object` in [method] that reads the string the friends card is built from:
 * a String field read, an `if-eqz` on that register, then the `new-instance` of the section that
 * names itself [FRIENDS_CARD_SECTION] a few instructions on. Null unless there's exactly one.
 */
internal fun friendsCardGuard(method: Method): Int? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val names = code.indices.filter { index ->
        code[index].opcode == Opcode.CONST_STRING &&
            ((code[index] as ReferenceInstruction).reference as? StringReference)?.string == FRIENDS_CARD_SECTION
    }
    val guards = names.mapNotNull { name ->
        val created = (name - 1 downTo maxOf(0, name - 8)).firstOrNull { code[it].opcode == Opcode.NEW_INSTANCE }
            ?: return@mapNotNull null
        val read = created - 2
        if (read < 0 || code[read].opcode != Opcode.IGET_OBJECT || code[created - 1].opcode != Opcode.IF_EQZ) {
            return@mapNotNull null
        }
        val field = (code[read] as ReferenceInstruction).reference as? FieldReference ?: return@mapNotNull null
        val register = (code[read] as TwoRegisterInstruction).registerA
        if (field.type != STRING || (code[created - 1] as OneRegisterInstruction).registerA != register) {
            return@mapNotNull null
        }
        read
    }
    return guards.singleOrNull()
}

/** The methods outside the extension holding [FRIENDS_CARD_SECTION] that build the card behind a guard. */
internal fun BytecodePatchContext.friendsCardBuilders(): List<Method> =
    classDefByStrings(FRIENDS_CARD_SECTION, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .flatMap { methodsHolding(it, FRIENDS_CARD_SECTION) }
        .filter { friendsCardGuard(it) != null }

/**
 * Hands the string the friends card is built from to the extension as it's read, in its own
 * register, so a null answer takes Facebook's own jump past the card. Refuses unless one method
 * builds the card that way.
 */
internal fun BytecodePatchContext.hookFindFriendsCard() {
    val builders = friendsCardBuilders()
    val method = builders.singleOrNull()
        ?: throw PatchException("expected one method building \"$FRIENDS_CARD_SECTION\" behind a string, found ${builders.size}")
    val read = friendsCardGuard(method)!!
    val register = (method.implementation!!.instructions.toList()[read] as TwoRegisterInstruction).registerA
    mutableClassDefBy(method.definingClass).findMutableMethodOf(method).addInstructions(
        read + 1,
        """
            invoke-static/range { v$register .. v$register }, $FRIENDS_CARD
            move-result-object v$register
        """,
    )
}
