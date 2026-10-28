/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/*
 * The numbers under a reel's Like, Comment and Share icons (#180). Read from 583, 2026-10-17, arm64
 * and armv7; the obfuscated names here are for reviewers, the code never writes one down.
 *
 * - Every button down a reel's right side is one Litho component (arm64 LX/ARU;, armv7 LX/ATD;) whose
 *   constructor hands the name FDSVerticalUfiButton to its base class. It takes two Strings, the
 *   text under the icon and then the accessibility label, and stores the first in a field render
 *   reads. With that text non-null render draws it under the icon (a method answering FDSText from
 *   a String); with it null the text row isn't built and the column is shorter.
 * - The constructor's callers are the main sidebar's static factory (LX/2ds;->A00, called from the
 *   reel sidebar builder) and four other sidebar builders. The share and comment texts come from
 *   lambdas, so the constructor is the one place every one of them passes through.
 * - The count formatter behind the text is shared by about ninety callers across the app, so the
 *   hook goes on the button, not the formatter.
 * - The hook goes first thing in the constructor: the text parameter (p10, v11 on both builds) goes
 *   through the extension and back into its own register before anything reads it.
 */

internal const val VERTICAL_UFI_BUTTON = "FDSVerticalUfiButton"
internal const val FDS_TEXT = "Lcom/facebook/fds/text/FDSText;"
internal const val REEL_COUNT_TEXT = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->countText(Ljava/lang/String;)Ljava/lang/String;"

private const val PATCH = "Clean up Reels"
private const val STRING = "Ljava/lang/String;"

/** Whether [classDef] has a method answering [FDS_TEXT] from a String first, the text under the icon. */
internal fun drawsUfiText(classDef: ClassDef): Boolean = classDef.methods.any {
    it.returnType == FDS_TEXT && it.parameterTypes.firstOrNull()?.toString() == STRING
}

/** The constructor of [classDef] that hands [VERTICAL_UFI_BUTTON] to the base class, or null. */
internal fun ufiButtonConstructor(classDef: ClassDef): Method? =
    classDef.methods.singleOrNull { it.name == "<init>" && holdsString(it, VERTICAL_UFI_BUTTON) }

/** The index of the button text among [constructor]'s parameters: the first of its exactly two Strings. */
internal fun buttonTextParameter(constructor: Method): Int? {
    val strings = constructor.parameterTypes.withIndex().filter { it.value.toString() == STRING }
    return strings.takeIf { it.size == 2 }?.first()?.index
}

/**
 * The String field [constructor] stores parameter [index] in, read by [classDef]'s render. Null
 * unless the constructor writes that register to exactly one String field of its own class and
 * render reads that field.
 */
internal fun textField(classDef: ClassDef, constructor: Method, index: Int): FieldReference? {
    val register = constructor.parameterRegisterNumber(index)
    val stores = constructor.implementation?.instructions?.filter {
        it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == register
    }?.mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
        ?.filter { it.definingClass == classDef.type && it.type == STRING }.orEmpty()
    val field = stores.singleOrNull() ?: return null
    val render = classDef.methods.singleOrNull { it.name == "render" } ?: return null
    val read = render.implementation?.instructions?.any {
        it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as? FieldReference)?.let { ref ->
            ref.definingClass == field.definingClass && ref.name == field.name && ref.type == field.type
        } == true
    } == true
    return field.takeIf { read }
}

/**
 * Hands the text parameter to the extension first thing and puts the answer back in its own
 * register. The range form takes any register, and move-result-object takes up to v255.
 */
internal fun MutableMethod.filterButtonText(index: Int) {
    val register = parameterRegisterNumber(index)
    if (register > 255) throw PatchException("$PATCH: $definingClass->$name holds the button text in v$register, past v255")
    addInstructions(
        0,
        """
            invoke-static/range { v$register .. v$register }, $REEL_COUNT_TEXT
            move-result-object v$register
        """,
    )
}

/**
 * Finds the vertical button by its kept literal and the FDSText it draws, holds its constructor to
 * two Strings with the first one stored where render reads it, and sends that text through the
 * extension. Refuses unless each step finds one.
 */
internal fun BytecodePatchContext.hideReelCounts() {
    val buttons = classDefByStrings(VERTICAL_UFI_BUTTON, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }
        .filter { ufiButtonConstructor(it) != null && drawsUfiText(it) }
        .distinctBy { it.type }
    val button = buttons.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one component whose constructor names $VERTICAL_UFI_BUTTON and which draws an FDSText, " +
            "found ${buttons.size}",
    )
    val constructor = ufiButtonConstructor(button)!!
    val index = buttonTextParameter(constructor) ?: throw PatchException(
        "$PATCH: ${button.type}'s constructor doesn't take exactly two Strings",
    )
    textField(button, constructor, index) ?: throw PatchException(
        "$PATCH: ${button.type}'s constructor doesn't store its first String where render reads it",
    )
    val method = mutableClassDefBy(button.type).methods.single {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == constructor.parameterTypes.map(CharSequence::toString)
    }
    method.filterButtonText(index)
}
