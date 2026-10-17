/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.patches.facebook.feed.holdsString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * Where the Menu's shortcut rows read their list of bookmarks, on 582.
 *
 * The rows above See more (Feeds, Marketplace, Reels, Groups and Meta AI on a phone checked
 * 2026-10-15) are one Litho section, BookmarksFolderSection (582 `8x5`), which the PRODUCTS group's
 * section adds with the group's bookmarks. None of it passes through the group section hooks, and
 * the rows aren't drawn by the bookmark component the Muse card hook is in. The section's children
 * builder (582 `A2F`) reads the list from a field of its own as it starts, then splits off the rows
 * shown above See more; the rest open under See more.
 *
 * The class is a Redex name, so the builder is found by the "BookmarksFolderSection" tag it gives
 * its event handlers and the "see_more_test_key" its See more row carries, and the read by its
 * shape: the one `iget-object` of an ImmutableList field the section's own class declares.
 */
internal const val FOLDER_SECTION = "BookmarksFolderSection"

/** The builder tags its See more row with this key. */
internal const val SEE_MORE_TEST_KEY = "see_more_test_key"

/** The folder section's children builder: an instance method of one parameter handing back an object, loading both strings. */
internal fun isFolderSectionChildren(method: Method): Boolean =
    method.implementation != null && !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.parameterTypes.size == 1 && method.returnType.startsWith("L") &&
        holdsString(method, FOLDER_SECTION) && holdsString(method, SEE_MORE_TEST_KEY)

/** Where [method] reads its bookmark list: the `iget-object` at [index], into [register]. */
internal data class BookmarkListRead(val index: Int, val register: Int)

/** The one read in [method] of an ImmutableList field its own class declares, or null unless there's exactly one. */
internal fun bookmarkListRead(method: Method): BookmarkListRead? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val reads = code.indices.filter { index ->
        val field = (code[index] as? ReferenceInstruction)?.reference as? FieldReference
        code[index].opcode == Opcode.IGET_OBJECT && field != null &&
            field.definingClass == method.definingClass && field.type == IMMUTABLE_LIST
    }
    val read = reads.singleOrNull() ?: return null
    return BookmarkListRead(read, (code[read] as OneRegisterInstruction).registerA)
}
