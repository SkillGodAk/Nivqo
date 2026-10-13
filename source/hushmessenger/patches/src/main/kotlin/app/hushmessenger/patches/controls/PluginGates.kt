package app.hushmessenger.patches.controls

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** Plugin gates by control, found by their kill switch or plugin names. False is the normal disabled path. */
internal data class PluginGate(val anchors: Set<String>)

internal const val AD_CONTEXT_BANNER = "ad_context_banner"

internal val pluginGates = mapOf(
    "people" to PluginGate(
        setOf(
            "com.facebook.messaging.friending.plugins.inboxunit.InboxPeopleYouMayKnowSectionKillSwitch",
        ),
    ),
    // The same suggestions repeated after the last chat; selected by the people control.
    "people_list_end" to PluginGate(
        setOf(
            "com.facebook.messaging.friending.plugins.inboxthreadlistend.InboxPYMKThreadListEndKillSwitch",
        ),
    ),
    "friend_requests" to PluginGate(
        setOf(
            "com.facebook.messaging.friending.plugins.friendrequestinboxunit.FriendingFriendrequestinboxunitKillSwitch",
        ),
    ),
    "growth" to PluginGate(
        setOf(
            "com.facebook.messaging.friending.plugins.growthpromotioninboxunit.FriendingGrowthpromotioninboxunitKillSwitch",
        ),
    ),
    "moments" to PluginGate(
        setOf(
            "com.facebook.messaging.navigation.plugins.momentsfolder.NavigationMomentsfolderKillSwitch",
        ),
    ),
    "ai_stickers" to PluginGate(
        setOf(
            "com.facebook.stickers.keyboardls.generatedtab.plugins.core.KeyboardlsGeneratedtabCoreKillSwitch",
            "com.facebook.messaging.suggestedkeyboard.plugins.core.composer.rows.genai.GenAiSearchSuggestedRow",
        ),
    ),
    "avatar_stickers" to PluginGate(
        setOf(
            "com.facebook.stickers.keyboardls.avatartab.plugins.core.KeyboardlsAvatartabCoreKillSwitch",
        ),
    ),
    "inbox_promotions" to PluginGate(
        setOf(
            "com.facebook.messaging.quickpromotion.plugins.threadlist.QuickpromotionThreadlistKillSwitch",
            "com.facebook.messaging.quickpromotion.plugins.threadlistmsys.QuickpromotionThreadlistmsysKillSwitch",
        ),
    ),
    "chat_promotions" to PluginGate(
        setOf(
            "com.facebook.messaging.quickpromotion.plugins.threadview.QuickpromotionThreadviewKillSwitch",
            "com.facebook.messaging.quickpromotion.plugins.threadviewmsys.QuickpromotionThreadviewmsysKillSwitch",
        ),
    ),
    // The ad a chat with a business started from, shown as a banner at the top of the chat. It isn't a Quickpromotion,
    // so the chat promotion gates never see it. The anchor is the banner itself, which only the thread banner gate builds.
    AD_CONTEXT_BANNER to PluginGate(
        setOf(
            "com.facebook.messaging.nativepagereply.plugins.adscontextprovider.threadviewbanner.CTMAdsThreadViewBannerImplementation",
        ),
    ),
    "suggested_replies" to PluginGate(
        setOf(
            "com.facebook.messaging.business.plugins.suggestedreply.SuggestedReplyKillSwitch",
        ),
    ),
    "business_suggestions" to PluginGate(
        setOf(
            "com.facebook.messaging.business.plugins.suggestasyoutype.SAYTKillSwitch",
        ),
    ),
    "event_prompts" to PluginGate(
        setOf(
            "com.facebook.messaging.events.plugins.qp.EventsQpKillSwitch",
        ),
    ),
    "reels_badge" to PluginGate(
        setOf(
            "com.facebook.messaging.reels.plugins.badge.ReelsBadgeKillSwitch",
        ),
    ),
    "ai_toolbar" to PluginGate(
        setOf(
            "com.facebook.messaging.inbox.tab.plugins.core.tabtoolbarbutton.aihomebutton.AiHomeButtonKillSwitch",
        ),
    ),
    // The Meta AI bottom tab. Its kill switch also gates the tab's own toolbar buttons, so the
    // anchor is the tab content, which only the bottom bar's gate builds.
    "ai_tab" to PluginGate(
        setOf(
            "com.facebook.messaging.aibot.plugins.tab.tabcontent.MetaAiTabContentImplementation",
        ),
    ),
)

/**
 * Redex's string tables: static (I)String methods that switch on the key straight to a constant, by method and key.
 * 582 moved some kill switch names into one, so a gate loads its anchor with `const/16 key` and a lookup, not a literal.
 */
internal fun redexStringTables(classes: Iterable<ClassDef>): Map<String, Map<Int, String>> = buildMap {
    for (method in classes.asSequence().flatMap { it.methods }) {
        if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Ljava/lang/String;" ||
            method.parameterTypes.map { it.toString() } != listOf("I")) continue
        val code = method.implementation?.instructions?.toList() ?: continue
        val switch = code.firstOrNull()?.takeIf { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH } as? OffsetInstruction
            ?: continue
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val indexAt = code.indices.associateBy { addresses[it] }
        val payload = indexAt[switch.codeOffset]?.let(code::get) as? SwitchPayload ?: continue
        val cases = payload.switchElements.mapNotNull { case ->
            val at = indexAt[case.offset] ?: return@mapNotNull null
            val load = code[at]
            val exit = code.getOrNull(at + 1)
            val string = ((load as? ReferenceInstruction)?.reference as? StringReference)?.string
            if (string == null || load.opcode !in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) || exit?.opcode != Opcode.RETURN_OBJECT ||
                (exit as OneRegisterInstruction).registerA != (load as OneRegisterInstruction).registerA) null else case.key to string
        }.toMap()
        if (cases.isNotEmpty()) put(method.hookId(), cases)
    }
}

/** The strings [this] looks up in a [redexStringTables] table by a constant key loaded right before the call. */
internal fun Method.tableStrings(tables: Map<String, Map<Int, String>>): Set<String> {
    val code = implementation?.instructions?.toList() ?: return emptySet()
    return (1 until code.size).mapNotNull { at ->
        val call = code[at] as? FiveRegisterInstruction ?: return@mapNotNull null
        val table = tables[(call as? ReferenceInstruction)?.reference?.toString()] ?: return@mapNotNull null
        val key = code[at - 1]
        if (call.opcode != Opcode.INVOKE_STATIC || call.registerCount != 1 ||
            key.opcode !in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST) || (key as? OneRegisterInstruction)?.registerA != call.registerC) null
        else table[(key as? NarrowLiteralInstruction)?.narrowLiteral]
    }.toSet()
}
