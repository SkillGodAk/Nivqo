/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.menu

import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Where the Menu's Help and support and Settings and privacy rows come from, on 583 (#153).
 *
 * The native group section (see MenuSectionAnchors.kt) switches on its group. Help and support
 * gets its rows from a builder taking only the user session (583 `LX/92y;->A05`, its 32-bit build
 * `LX/945;->A05`), Settings and privacy from the one SettingsRowAnchors.kt finds (`A08`), on the
 * same class. Both hand back an ImmutableList of the plain row items SettingsRowAnchors.kt
 * describes, and both keep that list in a field and hand it back on the next call, so the hook
 * filters the section's copy right after each call, never the kept list. Help's row ids are
 * constants; Settings' are constants and the server's bookmark ids.
 *
 * 584 inlined both builders into the native section (`LX/Lmh;->A2R`), so there's no call: the
 * section reads and writes the kept lists itself, on the class the builders were on (`LX/AgD;`).
 * Its copy of each list is the one it just read from the field or just wrote to it ([keptRowLists]).
 */

/** The Help and support row list build: a virtual call on [owner] taking only the user session and handing back an ImmutableList. */
internal fun isHelpRowListBuild(call: MethodReference, owner: String): Boolean =
    call.definingClass == owner && call.returnType == IMMUTABLE_LIST &&
        call.parameterTypes.map { it.toString() } == listOf(USER_SESSION)

/**
 * Where [method] keeps a row list: the `move-result-object` at [index], into [register], or on 584
 * the read or write of a kept list there, into or from [register].
 */
internal data class RowListRead(val index: Int, val register: Int)

private val VIRTUAL_CALLS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)

/**
 * The result of each virtual call in [method] that [wanted] picks, in order, or null when one of
 * them isn't kept by a `move-result-object` right after it.
 */
internal fun rowListReads(method: Method, wanted: (MethodReference) -> Boolean): List<RowListRead>? {
    val code = method.implementation?.instructions?.toList() ?: return null
    return code.indices.filter { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        code[at].opcode in VIRTUAL_CALLS && call != null && wanted(call)
    }.map { at ->
        val result = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } ?: return null
        RowListRead(at + 1, (result as OneRegisterInstruction).registerA)
    }
}

/** The types [method] constructs. */
internal fun constructedTypes(method: Method): Set<String> =
    method.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.NEW_INSTANCE }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        .toSet()

/**
 * Whether [item] is a row item the extension can read: a full constructor over a title, two
 * addresses, icons and an id, and exactly one instance field of type CharSequence (the title)
 * and one of type long (the id), which are what MenuRows looks up.
 */
internal fun isReadableRowItem(item: ClassDef): Boolean {
    val instance = item.fields.filterNot { AccessFlags.STATIC.isSet(it.accessFlags) }
    return fullConstructor(item) != null &&
        instance.count { it.type == "Ljava/lang/CharSequence;" } == 1 && instance.count { it.type == "J" } == 1
}

/**
 * The two lists 584's native section keeps on the builders' old class: Settings and privacy's
 * ([settings], 583's `A08` kept it beside a Boolean of the flag it was built for, and the section
 * writes that Boolean right after the list) and Help and support's ([help], 583's `A05` was
 * synchronized, so the section reads it right after taking the holder's monitor).
 */
internal data class KeptRowLists(val settings: FieldReference, val help: FieldReference)

private const val BOOLEAN = "Ljava/lang/Boolean;"

private val FIELD_WRITES = setOf(
    Opcode.IPUT, Opcode.IPUT_WIDE, Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN, Opcode.IPUT_BYTE, Opcode.IPUT_CHAR,
    Opcode.IPUT_SHORT, Opcode.SPUT, Opcode.SPUT_WIDE, Opcode.SPUT_OBJECT, Opcode.SPUT_BOOLEAN, Opcode.SPUT_BYTE,
    Opcode.SPUT_CHAR, Opcode.SPUT_SHORT,
)

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun FieldReference.key() = "$definingClass->$name:$type"

/** The object a field read or write at [at] goes through. */
private fun List<Instruction>.holder(at: Int) = (this[at] as TwoRegisterInstruction).registerB

/**
 * [native]'s kept Settings and Help lists: ImmutableList fields of one class. Settings' is the one
 * where the next field write after each write of the list, before the code jumps away, puts a
 * Boolean of the same class in the same object. Help's is the one each read of which comes right
 * after a `monitor-enter` of the object read. Neither rule minds the list hooks put after a read or
 * write, which write no field. Null unless there's exactly one of each and the section reads each
 * once and writes each once.
 */
internal fun keptRowLists(native: Method): KeptRowLists? {
    val code = native.implementation?.instructions?.toList() ?: return null
    fun accesses(opcode: Opcode) = code.indices
        .filter { at -> code[at].opcode == opcode && code[at].field()?.type == IMMUTABLE_LIST }
        .groupBy { at -> code[at].field()!!.key() }
    val reads = accesses(Opcode.IGET_OBJECT)
    val writes = accesses(Opcode.IPUT_OBJECT)
    val settings = writes.filterValues { at ->
        at.all { write ->
            val straight = (write + 1 until code.size).takeWhile { code[it - 1].opcode.canContinue() }
            val next = straight.firstOrNull { code[it].field() != null && code[it].opcode in FIELD_WRITES }
            val flag = next?.let { code[it].field() }
            next != null && code[next].opcode == Opcode.IPUT_OBJECT && flag?.type == BOOLEAN &&
                flag.definingClass == code[write].field()!!.definingClass && code.holder(next) == code.holder(write)
        }
    }.keys.singleOrNull() ?: return null
    val help = reads.filterValues { at ->
        at.all { read ->
            read > 0 && code[read - 1].opcode == Opcode.MONITOR_ENTER &&
                (code[read - 1] as OneRegisterInstruction).registerA == code.holder(read)
        }
    }.keys.singleOrNull() ?: return null
    if (settings == help || listOf(settings, help).any { reads[it]?.size != 1 || writes[it]?.size != 1 }) return null
    val settingsField = code[writes.getValue(settings).single()].field()!!
    val helpField = code[reads.getValue(help).single()].field()!!
    if (settingsField.definingClass != helpField.definingClass) return null
    return KeptRowLists(settingsField, helpField)
}

/**
 * Where [method] has its own copy of the list kept in [field]: each read of the field and each
 * write to it, in order, with the register the list is read into or written from. Null when the
 * instruction after one can be reached any other way than from it, since a hook there would miss
 * that way in.
 */
internal fun keptListReads(method: Method, field: FieldReference): List<RowListRead>? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val sites = code.indices.filter { at ->
        (code[at].opcode == Opcode.IGET_OBJECT || code[at].opcode == Opcode.IPUT_OBJECT) &&
            code[at].field()?.key() == field.key()
    }
    val flow = ControlFlow.of(method)
    val into = Array(code.size) { mutableListOf<Int>() }
    for (from in code.indices) {
        flow.normal[from].forEach { into[it] += from }
        flow.exceptional[from].forEach { into[it] += from }
    }
    if (sites.any { at -> at + 1 >= code.size || into[at + 1] != listOf(at) }) return null
    return sites.map { at -> RowListRead(at, (code[at] as OneRegisterInstruction).registerA) }
}
