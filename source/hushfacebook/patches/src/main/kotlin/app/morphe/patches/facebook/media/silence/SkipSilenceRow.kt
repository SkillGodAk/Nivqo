/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.silence

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.downloads.reel.MoreSheet
import app.morphe.patches.facebook.downloads.reel.implementClick
import app.morphe.patches.facebook.downloads.reel.rowArguments
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireFreeAt
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/** The extension class that answers the row and handles its tap. */
internal const val SKIP_ROW_HANDLER = "$EXTENSION_PACKAGE/media/SkipSilenceRow;"

/** The id the added row carries, in the place Facebook's rows carry theirs. */
internal const val SKIP_ROW_ID = "hushfacebook_skip_silence"

/** The helper this adds to the sheet builder's class. */
internal const val SKIP_ROW_HELPER = "hushfacebookSkipSilenceRow"

private const val LIST = "Ljava/util/List;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * Puts a Skip silent parts row in a reel's More sheet, with the other rows Hushfacebook adds there,
 * drawn with Clear mode's icon. A helper on the builder's class hands the reel's video id (the
 * builder's one String) to [SKIP_ROW_HANDLER] and builds the row when it gets a handler back, which
 * it does only while the switch is on and a video has started. The handler class is made to
 * implement the row's click interface, with the interface's one method calling its `run()`.
 */
internal fun BytecodePatchContext.addSkipSilenceRow(patch: String, sheet: MoreSheet) {
    val builder = sheet.builder
    val parameters = builder.parameterTypes.map(CharSequence::toString)
    fun parameter(type: String, what: String) = parameters.indices.filter { parameters[it] == type }
        .singleOrPatchException("$patch: the one $what parameter of ${builder.definingClass}->${builder.name}")
    val id = parameter(STRING, "video id")
    val list = parameter(LIST, "row list")

    // The two values go to v0 and v1 first thing in the builder, proved free there before anything changes.
    builder.requireFreeAt(patch, 0, listOf(0, 1))
    implementClick(sheet, SKIP_ROW_HANDLER)

    // The helper, with fresh registers: v0 to v7 for the handler, v8 up for the row. The title is
    // read off the handler before it's cast to the click interface.
    val rowParameters = sheet.row.parameterTypes.map(CharSequence::toString)
    val helperParameters = listOf(STRING, LIST)
    val locals = maxOf(16, 9 + rowParameters.size)
    val icon = sheet.clearModeIcon
    val wrap = sheet.iconWrap
    val helper = ImmutableMethod(
        builder.definingClass,
        SKIP_ROW_HELPER,
        helperParameters.map { ImmutableMethodParameter(it, null, null) },
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(locals + helperParameters.size),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                move-object/from16 v0, p0
                invoke-static { v0 }, $SKIP_ROW_HANDLER->forReel($STRING)$SKIP_ROW_HANDLER
                move-result-object v7
                if-eqz v7, :done
                invoke-virtual { v7 }, $SKIP_ROW_HANDLER->title()$STRING
                move-result-object v5
                check-cast v7, ${sheet.click}
                sget-object v6, ${icon.definingClass}->${icon.name}:${icon.type}
                invoke-static { v6 }, ${wrap.definingClass}->${wrap.name}(${wrap.parameterTypes.single()})${wrap.returnType}
                move-result-object v6
                new-instance v8, ${sheet.row.definingClass}
${rowArguments(rowParameters, sheet.click, wrap.returnType, SKIP_ROW_ID)}
                invoke-direct/range { v8 .. v${8 + rowParameters.size} }, ${sheet.row.definingClass}-><init>(${rowParameters.joinToString("")})V
                move-object/from16 v0, p1
                invoke-interface { v0, v8 }, $LIST->add($OBJECT)Z
                :done
                return-void
            """,
        )
    }
    mutableClassDefBy(builder.definingClass).methods.add(helper)

    // First thing in the builder, before its own checks.
    builder.addInstructions(
        0,
        """
            move-object/from16 v0, ${builder.parameterRegister(id)}
            move-object/from16 v1, ${builder.parameterRegister(list)}
            invoke-static { v0, v1 }, ${builder.definingClass}->$SKIP_ROW_HELPER(${helperParameters.joinToString("")})V
        """,
    )
}
