/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.tableStringAt
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Where the clean-up switches of Facebook's own Chats hook, on 582. Chats is
 * `messaginginblue`'s InboxActivity, the same list the Get Messenger card sits in (see
 * MessengerCardAnchors.kt).
 *
 * The row of tiles above the chats, friends' notes and who's active now, lives in one immutable
 * state object that Chats rebuilds on every result: a note result, an active-now result, a story
 * result. Its constructor takes the builder, null-checks the builder's tile list with the Kotlin
 * parameter name `activeNowTiles` (kept, because the check carries it as a literal) and stores the
 * list. The component that draws the row and the section builder both read
 * that field, so the patch hands the constructor an empty list while the switch is on and nothing
 * downstream has a tile to draw. That empties the list the row is built from: friends' notes and
 * who's active now. Your own "Your note" tile isn't in that list (seen on the 582 emulator), so the
 * row stays with that one tile. The state class and its builder are Redex names (`EB8` and
 * `EB7` on 582, renamed every build); only the literal and the constructor's shape find them.
 *
 * Chats also builds a group of top banners, each its own plugin answering a show question. Two of
 * them are promotions: the one asking you to turn on notifications (its builder tags the banner
 * `mib-notification-push-upsell-banner`) and the progressive diode card (`inbox_progressive_diode_top_view`).
 * Each tag is loaded by one builder in one plugin class, which also carries the show question the
 * Get Messenger card hook uses. The patch has each of them answer no while the switch is on, and
 * the list takes the next banner that says yes, as it does without them.
 *
 * The search row at the top of the chat list holds the search box and, when Facebook's server says
 * so, a Meta AI button. On 582 one component builds both: it tags the search box
 * `search_bar_text_input`, then builds the button with a tap action that opens Meta AI from the
 * inbox (the action reports `meta_ai_icon_clicked` and calls the kept MetaAIThreadViewLauncher),
 * checks the built button for null and adds it to the row's children. The patch hands that null
 * check nothing while the switch is on, so the row is built with the search box alone.
 *
 * Tapping the search box opens Chats' search screen (the people picker), whose row has a Meta AI
 * button of its own, built differently: the row sets a flag to 1 when it's in search mode and
 * nothing is typed, tests it once to shorten the search box for the button and again to build the
 * button. Its tap action opens Meta AI with the entry `messaging_inbox_in_blue:people_picker`
 * right after reading the row's own CallerContext, which names the row's class. The patch hands
 * the flag to the extension right after it's set, so with the switch on neither test sees it.
 *
 * Not hooked, and why: Messenger's inbox ad filter and quick-promotion kill switches are literals
 * that don't exist in Facebook's dex, so there is no inbox ad or quick-promotion plugin to anchor,
 * and no literal ties Meta AI's chat list entry or the AI sticker tab to one method.
 */
internal const val CHAT_LIST_PATCH = "Clean up Facebook's chat list"

/** The Kotlin parameter name the tile state's constructor checks its list against. */
internal const val NOTES_TILES = "activeNowTiles"

/** The tags the two promotion banners' builders load. */
internal const val PUSH_UPSELL_TAG = "mib-notification-push-upsell-banner"
internal const val DIODE_TAG = "inbox_progressive_diode_top_view"
internal val PROMOTION_TAGS = listOf(PUSH_UPSELL_TAG, DIODE_TAG)

internal const val IMMUTABLE_LIST_TYPE = "Lcom/google/common/collect/ImmutableList;"

/** Where the tile list is stored: the constructor, the iput-object's index and the register holding the list. */
internal class NotesTrayStore(val method: Method, val store: Int, val list: Int)

/**
 * The tile state's constructor, when [method] is it: a constructor of one parameter that loads
 * `activeNowTiles`, passes a register and that string to a call, and then stores that register
 * into an ImmutableList field of its own class. Null for any other method.
 */
internal fun notesTrayStore(owner: ClassDef, method: Method): NotesTrayStore? {
    if (method.name != "<init>" || method.parameterTypes.size != 1 || !holdsString(method, NOTES_TILES)) return null
    if (AccessFlags.STATIC.isSet(method.accessFlags)) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val load = code.indexOfFirst {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == NOTES_TILES
    }
    if (load < 0) return null
    val text = (code[load] as OneRegisterInstruction).registerA
    val check = (load + 1 until code.size).firstOrNull { at ->
        val call = code[at] as? FiveRegisterInstruction
        call != null && call.opcode == Opcode.INVOKE_STATIC && call.registerCount == 2 && call.registerD == text
    } ?: return null
    val list = (code[check] as FiveRegisterInstruction).registerC
    val store = (check + 1 until code.size).firstOrNull { at ->
        val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
        code[at].opcode == Opcode.IPUT_OBJECT && field != null && field.definingClass == owner.type &&
            field.type == IMMUTABLE_LIST_TYPE && (code[at] as OneRegisterInstruction).registerA == list
    } ?: return null
    return NotesTrayStore(method, store, list)
}

/** What the search row's Meta AI tap reports. */
internal const val META_AI_ICON_CLICKED = "meta_ai_icon_clicked"

/** The tag the search row gives its search box. */
internal const val SEARCH_BAR_TAG = "search_bar_text_input"

/** Kept name. What every Meta AI chat opens through. */
internal const val META_AI_LAUNCHER = "Lcom/facebook/messaginginblue/aibot/launcher/MetaAIThreadViewLauncher;"

/** Whether [owner] opens Meta AI from the search row: one of its methods reports the tap and calls the launcher. */
internal fun opensMetaAiFromSearchRow(owner: ClassDef): Boolean = owner.methods.any { method ->
    holdsString(method, META_AI_ICON_CLICKED) && method.implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == META_AI_LAUNCHER
    } == true
}

/**
 * Where the search row's builder gets its Meta AI button: the `move-result-object` at [result] in
 * [method] puts the built button in [register], as the builder's own return type [type].
 */
internal class MetaAiButtonSite(val method: Method, val result: Int, val register: Int, val type: String)

/** What the search row's button build takes: nothing, or on 583's 32-bit build the row's CallerContext. */
private val BUILD_PARAMETERS = listOf(emptyList<String>(), listOf(CALLER_CONTEXT))

/**
 * The Meta AI button site of [method] when it's the search row's builder: it tags its search box
 * [SEARCH_BAR_TAG] and creates one tap action of the [openers] types. The first build after that,
 * a call taking nothing (or a CallerContext) and answering the builder's own return type, puts the button in a
 * register that is checked for null and then added to the row's children. Null for any other
 * method.
 */
internal fun metaAiButtonSite(method: Method, openers: Set<String>): MetaAiButtonSite? {
    if (!holdsString(method, SEARCH_BAR_TAG)) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    val created = code.indices.filter { at ->
        code[at].opcode == Opcode.NEW_INSTANCE && ((code[at] as ReferenceInstruction).reference as? TypeReference)?.type in openers
    }
    if (created.size != 1) return null
    val build = (created.single() + 1 until code.size).firstOrNull { at ->
        val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        code[at].opcode == Opcode.INVOKE_VIRTUAL && call != null && call.returnType == method.returnType &&
            call.parameterTypes.map { it.toString() } in BUILD_PARAMETERS
    } ?: return null
    val result = code.getOrNull(build + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val register = (result as OneRegisterInstruction).registerA
    val check = code.getOrNull(build + 2)
    if (check?.opcode != Opcode.IF_EQZ || (check as OneRegisterInstruction).registerA != register) return null
    val add = code.getOrNull(build + 3)
    val adds = (add as? ReferenceInstruction)?.reference as? MethodReference
    if (add !is FiveRegisterInstruction || adds?.name != "add" || !adds.definingClass.startsWith("Ljava/util/") ||
        add.registerCount != 2 || add.registerD != register) return null
    return MetaAiButtonSite(method, build + 1, register, method.returnType)
}

/** What the Chats search screen's Meta AI tap names as where the chat was opened from. */
internal const val PEOPLE_PICKER_ENTRY = "messaging_inbox_in_blue:people_picker"

/** Kept name. Each Litho component keeps its own as a static field. */
internal const val CALLER_CONTEXT = "Lcom/facebook/common/callercontext/CallerContext;"

/** Whether [method] calls Meta AI's launcher, as the search screen's Meta AI tap does. */
internal fun callsMetaAiLauncher(method: Method): Boolean = method.implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == META_AI_LAUNCHER
} == true

/**
 * The class of the Chats search screen's row, named by the Meta AI tap in [owner]: a method that
 * calls the launcher and loads [PEOPLE_PICKER_ENTRY], or on 583's 64-bit build asks a string table
 * [resolve] finds for it ([tableStringAt]), with the row's own static CallerContext read in the same
 * straight run of code just before the entry. Null when [owner] holds no such tap.
 */
internal fun pickerRowOf(owner: ClassDef, resolve: (MethodReference) -> Method? = { null }): String? {
    val rows = owner.methods.flatMap { method ->
        if (!callsMetaAiLauncher(method)) return@flatMap emptyList()
        val code = method.implementation!!.instructions.toList()
        code.indices.filter {
            ((code[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == PEOPLE_PICKER_ENTRY ||
                tableStringAt(code, it, resolve) == PEOPLE_PICKER_ENTRY
        }.map { load -> callerContextBefore(code, load) }
    }
    return rows.singleOrNull()
}

/** The class whose static CallerContext is read before [at] with no jump, return or throw between, or null. */
private fun callerContextBefore(code: List<Instruction>, at: Int): String? {
    for (before in at - 1 downTo 0) {
        val instruction = code[before]
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        if (instruction.opcode == Opcode.SGET_OBJECT && field?.type == CALLER_CONTEXT) return field.definingClass
        if (instruction is OffsetInstruction || !instruction.opcode.canContinue()) return null
    }
    return null
}

/**
 * Where the search screen's row decides to show its Meta AI button: [set] in [method] is the one
 * instruction putting 1 in [register], the flag the row tests once to give the search box an end
 * margin and again at [test] before it builds the button.
 */
internal class PickerButtonGate(val method: Method, val set: Int, val test: Int, val register: Int)

/**
 * The Meta AI gate of [method] when it's the search screen row's render: it creates one tap action
 * of type [opener], the last `if-eqz` before that jumps past it tests the flag, no jump lands on that
 * test, and exactly one literal 1 is put in that register before it. Null for any other method.
 */
internal fun pickerButtonGate(method: Method, opener: String): PickerButtonGate? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val created = code.indices.filter { at ->
        code[at].opcode == Opcode.NEW_INSTANCE && ((code[at] as ReferenceInstruction).reference as? TypeReference)?.type == opener
    }
    if (created.size != 1) return null
    val tap = created.single()
    val address = IntArray(code.size + 1)
    for (at in code.indices) address[at + 1] = address[at] + code[at].codeUnits
    val gate = (tap - 1 downTo 0).firstOrNull { at ->
        code[at].opcode == Opcode.IF_EQZ && address[at] + (code[at] as OffsetInstruction).codeOffset > address[tap]
    } ?: return null
    val lands = code.indices.any { at ->
        val jump = code[at] as? OffsetInstruction
        jump != null && address[at] + jump.codeOffset == address[gate]
    }
    if (lands) return null
    val register = (code[gate] as OneRegisterInstruction).registerA
    val sets = (0 until gate).filter { at ->
        code[at].opcode in LITERAL_CONSTS && (code[at] as OneRegisterInstruction).registerA == register &&
            (code[at] as NarrowLiteralInstruction).narrowLiteral == 1
    }
    return sets.singleOrNull()?.let { PickerButtonGate(method, it, gate, register) }
}

private val LITERAL_CONSTS = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

/** The tile state constructor of [owner], or null when it isn't the state class. */
internal fun notesTrayOf(owner: ClassDef): NotesTrayStore? =
    owner.methods.mapNotNull { notesTrayStore(owner, it) }.singleOrNull()

private fun Method.isInstanceWithBody(): Boolean =
    implementation != null && !AccessFlags.STATIC.isSet(accessFlags) && !AccessFlags.ABSTRACT.isSet(accessFlags)

/** A banner plugin's builder: an instance method taking the list's `ThreadListParams` that loads [tag]. */
internal fun buildsBanner(method: Method, tag: String): Boolean =
    method.isInstanceWithBody() && method.returnType.startsWith("L") &&
        THREAD_LIST_PARAMS in method.parameterTypes.map { it.toString() } && holdsString(method, tag)

/**
 * The show question of the plugin that builds the banner tagged [tag], when [owner] is that
 * plugin: one builder loading the tag and one show question. Null for any other class.
 */
internal fun bannerQuestion(owner: ClassDef, tag: String): Method? {
    if (owner.methods.count { buildsBanner(it, tag) } != 1) return null
    return owner.methods.filter(::isShowQuestion).singleOrNull()
}
