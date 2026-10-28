/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.sharesheet

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The share sheet's row of people to send to, read from 583 on 2026-10-17 (#169). The obfuscated
 * names are for reviewers; the code finds the row by a kept name and a method shape.
 *
 * The sheet's body render (583 arm64 LX/Zya;->render, armv7 LX/ZkL;->render, the one loading
 * "messenger_suggested_recipients_hscroll") adds the row of round profile pictures to the list of
 * children it is collecting in three places, and each add is a hook: with the switch on, the row
 * never joins the children.
 *
 * Two go through a helper of its own class, `A0E(?, ?, List, int, boolean)`, which returns the
 * row's component: the row built from a cache and the shimmer placeholder drawn while people load
 * ("..._grid_glimmer"). Both are `invoke-static` of that helper, `move-result-object`, then
 * `AbstractCollection.add` in the next two instructions.
 *
 * The third is the one a share sheet takes when it opens (seen in a method trace of 583 on the
 * emulator, 2026-10-17): the render builds the row's model from the list of people
 * (arm64 `new LX/ZHc;(..., List, float)`, armv7 `LX/YwX;`), hands it to a one-argument builder
 * (`LX/b8v;->A01(LX/ZHc;)LX/ZHI;`, armv7 `LX/ad2;->A01`), and adds what that returns.
 */

/** The extension class the hook asks. */
internal const val SHARE_SHEET_PEOPLE = "$EXTENSION_PACKAGE/misc/ShareSheetPeople;"
internal const val ADD_PEOPLE_ROW =
    "$SHARE_SHEET_PEOPLE->addRow(Ljava/util/AbstractCollection;Ljava/lang/Object;)Z"

/** A Litho component's `render`, a name the framework keeps. */
private const val RENDER = "render"

private const val PATCH = "Share sheet items (people row)"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The share sheet's row of people goes through the extension's ShareSheetPeople. Nameless, so Share
 * sheet items carries it and its fixture tests run it on its own. Everything is found before
 * anything changes, and it refuses unless the render has exactly the two helper adds and the one
 * built from the list of people.
 */
internal val shareSheetPeoplePatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        hookShareSheetPeople()
    }
}

/** Where a people row joins its parent's children: the `add` instruction's index and its two registers. */
internal data class PeopleRowAdd(val index: Int, val children: Int, val row: Int)

internal fun BytecodePatchContext.hookShareSheetPeople() {
    val bodies = methodsNaming(SHARE_BODY_ANCHOR) { it.name == RENDER }
    val body = bodies.singleOrNull() ?: refuse("expected one render loading \"$SHARE_BODY_ANCHOR\", found ${bodies.size}")
    val helperAdds = helperRowAdds(body)
    if (helperAdds.size != 2) refuse("expected the cached people row and its placeholder in the share sheet body, found ${helperAdds.size}")
    val listAdds = listRowAdds(body)
    if (listAdds.size != 1) refuse("expected one people row built from its list in the share sheet body, found ${listAdds.size}")

    val method = mutableClassDefBy(body.definingClass).findMutableMethodOf(body)
    (helperAdds + listAdds).forEach { add ->
        method.replaceInstruction(add.index, "invoke-static { v${add.children}, v${add.row} }, $ADD_PEOPLE_ROW")
    }
}

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

/** Every place [render] adds a people row to its children, in code order. */
internal fun peopleRowAdds(render: Method): List<PeopleRowAdd> =
    (helperRowAdds(render) + listRowAdds(render)).sortedBy { it.index }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

/**
 * The two adds of a row from the render's own helper: a call to a static helper of its class that
 * takes `(?, ?, List, int, boolean)` and returns what `render` returns, whose result goes straight
 * into an `AbstractCollection.add`.
 */
internal fun helperRowAdds(render: Method): List<PeopleRowAdd> {
    val code = render.code()
    return (0 until code.size - 2).mapNotNull { index ->
        val call = code[index].reference() as? MethodReference ?: return@mapNotNull null
        if (code[index].opcode != Opcode.INVOKE_STATIC && code[index].opcode != Opcode.INVOKE_STATIC_RANGE) return@mapNotNull null
        if (call.definingClass != render.definingClass || call.returnType != render.returnType) return@mapNotNull null
        val parameters = call.parameterTypes.map(CharSequence::toString)
        if (parameters.size != 5 || parameters.subList(2, 5) != listOf("Ljava/util/List;", "I", "Z")) return@mapNotNull null
        resultAdded(code, index)
    }
}

/** How far before the builder call the row model's constructor may sit (583 has one move between). */
private const val MODEL_REACH = 3

/**
 * The add of a row built from the list of people: the render constructs a model whose constructor
 * takes a `List` among its arguments, a one-argument virtual method that takes that model returns
 * the row, and the row goes straight into an `AbstractCollection.add`.
 */
internal fun listRowAdds(render: Method): List<PeopleRowAdd> {
    val code = render.code()
    return (0 until code.size - 2).mapNotNull { index ->
        val opcode = code[index].opcode
        if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return@mapNotNull null
        val call = code[index].reference() as? MethodReference ?: return@mapNotNull null
        val model = call.parameterTypes.singleOrNull()?.toString() ?: return@mapNotNull null
        if (!model.startsWith("L") || model.startsWith("Ljava/") || !call.returnType.startsWith("L")) return@mapNotNull null
        val built = (maxOf(0, index - MODEL_REACH) until index).any { before ->
            val init = code[before].reference() as? MethodReference
            (code[before].opcode == Opcode.INVOKE_DIRECT || code[before].opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                init != null && init.definingClass == model && init.name == "<init>" &&
                init.parameterTypes.any { it.toString() == "Ljava/util/List;" }
        }
        if (!built) return@mapNotNull null
        resultAdded(code, index)
    }
}

/** The add right after the call at [index], when that call's result goes straight into `AbstractCollection.add`. */
private fun resultAdded(code: List<Instruction>, index: Int): PeopleRowAdd? {
    val moved = code[index + 1]
    if (moved.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val add = code[index + 2]
    val target = add.reference() as? MethodReference ?: return null
    if (add.opcode != Opcode.INVOKE_VIRTUAL || target.definingClass != "Ljava/util/AbstractCollection;" ||
        target.name != "add" || target.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/Object;")
    ) return null
    val registers = add as? FiveRegisterInstruction ?: return null
    if (registers.registerCount != 2) return null
    val row = (moved as OneRegisterInstruction).registerA
    if (registers.registerD != row) return null
    return PeopleRowAdd(index + 2, registers.registerC, registers.registerD)
}
