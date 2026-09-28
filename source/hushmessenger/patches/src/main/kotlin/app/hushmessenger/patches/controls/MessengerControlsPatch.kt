package app.hushmessenger.patches.controls

import app.hushmessenger.patches.MessengerTarget
import app.hushmessenger.patches.coexist.validateVersionCode
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.Method
import org.w3c.dom.Document
import org.w3c.dom.Element

internal fun Document.addSettingsEntry() {
    val applications = getElementsByTagName("application")
    if (applications.length != 1) throw PatchException("Messenger controls: expected one application")
    val application = applications.item(0) as Element
    for (tag in listOf("activity", "provider")) {
        val nodes = getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            if ((nodes.item(i) as Element).getAttribute("android:name").startsWith("app.hushmessenger.extension.")) {
                throw PatchException("Messenger controls: settings are already installed. Start with the stock APK.")
            }
        }
    }
    fun Element.child(tag: String, vararg attributes: Pair<String, String>): Element = createElement(tag).also { node ->
        attributes.forEach { (name, value) -> node.setAttribute("android:$name", value) }
        appendChild(node)
    }
    application.child("provider", "name" to "app.hushmessenger.extension.SettingsProvider",
        "authorities" to "com.facebook.orca.hush.settings", "exported" to "false")
    // Settings is deliberately not a launcher activity and this fork no longer installs
    // Messenger long-press shortcuts. Access belongs inside Messenger's own settings UI.
    application.child("activity", "name" to "app.hushmessenger.extension.SettingsActivity",
        "label" to "HushMessenger settings", "exported" to "true",
        "taskAffinity" to "app.hushmessenger.settings")
    application.child("activity", "name" to "app.hushmessenger.extension.RestartActivity",
        "label" to "Restart Messenger", "exported" to "true", "excludeFromRecents" to "true",
        "noHistory" to "true", "configChanges" to "orientation|screenSize|keyboardHidden",
        "theme" to "@android:style/Theme.Material.NoActionBar")
}

private val settingsResources = resourcePatch(description = "Install HushMessenger settings") {
    execute {
        validateVersionCode(packageMetadata.versionCode)
        document("AndroidManifest.xml").use { manifest -> manifest.addSettingsEntry() }
    }
}

private var discoveredControls: Map<String, List<Method>> = emptyMap()
private var discoveredSettingsEntry: List<Method> = emptyList()

private val settingsExtension = bytecodePatch(description = "Load HushMessenger runtime controls") {
    dependsOn(settingsResources)
    extendWith("extensions/messenger.mpe")
    execute {
        val classes = mutableListOf<com.android.tools.smali.dexlib2.iface.ClassDef>()
        classDefForEach { classes.add(it) }
        discoveredControls = findControls(classes)
        discoveredSettingsEntry = findSettingsEntryHooks(classes)
        // Do not fail the whole Messenger patch when the in-app settings row fingerprint is not
        // unique for this Messenger build. The embedded SettingsActivity/Provider still ship with
        // the patched app, while normal controls patches can continue to apply. A precise
        // Messenger 580 row hook must be added before this entry can be guaranteed in the native
        // settings screen.
        if (discoveredSettingsEntry.size == 1) {
            val hook = discoveredSettingsEntry.single()
            val mutableHook = mutableClassDefBy(hook.definingClass).methods.single { it.hookId() == hook.hookId() }
            mutableHook.injectSettingsEntry()
        } else {
            discoveredSettingsEntry
                .distinctBy { it.hookId() }
                .forEach { hook ->
                    runCatching {
                        mutableClassDefBy(hook.definingClass)
                            .methods
                            .single { it.hookId() == hook.hookId() }
                            .injectSettingsEntryInstaller()
                    }
                }
        }
    }
    finalize {
        discoveredControls = emptyMap()
        discoveredSettingsEntry = emptyList()
    }
}

private fun Document.requireFeatureAbsent(key: String): Element {
    val application = getElementsByTagName("application").item(0) as Element
    val name = "hush.feature.$key"
    val metadata = application.getElementsByTagName("meta-data")
    if ((0 until metadata.length).any { (metadata.item(it) as Element).getAttribute("android:name") == name }) {
        throw PatchException("HushMessenger: $key is already installed. Start with the stock APK.")
    }
    return application
}

internal fun Document.addFeature(key: String) {
    val application = requireFeatureAbsent(key)
    application.appendChild(createElement("meta-data").apply {
        setAttribute("android:name", "hush.feature.$key")
        setAttribute("android:value", "true")
    })
}

internal fun injectControl(key: String, methods: Map<String, List<MutableMethod>>) {
    // A multi-method control must pass every contract before its first edit.
    for ((hook, selectedMethods) in methods) for (method in selectedMethods) {
        if (hook in pluginGates) method.validatePluginGate()
        when (hook) {
            "subtabs" -> method.validateSubtabs()
            "browser" -> method.validateBrowserPreference()
            "ads" -> method.validateAdFilter()
            "people_jewel" -> method.validatePeopleSection()
            else -> method.validateSwitch()
        }
    }
    for ((hook, selectedMethods) in methods) for (method in selectedMethods) {
        when (hook) {
            "subtabs" -> method.injectSubtabs()
            "browser" -> method.injectBrowserPreference()
            "ads" -> method.injectAdFilter()
            "people_jewel" -> method.injectPeopleSection()
            "stories" -> method.injectSwitch("hideStories", "0x0")
            "facebook" -> method.injectSwitch("hideFacebook", "0x0")
            "ai_menu", "ai_fab", "ai_toolbar" -> method.injectSwitch("hideMetaAi", "0x0")
            "typing" -> method.injectSwitch("suppressTyping", "0x0")
            "bubbles" -> method.injectSwitch("enableBubbles", "0x1")
            else -> method.injectFeatureSwitch(key)
        }
    }
}

private fun controlPatch(key: String, title: String, summary: String, group: String, vararg hooks: String): BytecodePatch {
    var applied = false
    val featureResources = resourcePatch(description = "Record HushMessenger capability: $key") {
        dependsOn(settingsResources)
        execute {
            applied = false
            document("AndroidManifest.xml").use { it.requireFeatureAbsent(key) }
        }
        finalize {
            if (applied) document("AndroidManifest.xml").use { it.addFeature(key) }
        }
    }
    return bytecodePatch(
        name = title,
        description = "$summary Open Messenger settings > HushMessenger settings. Starts off.",
        default = true,
    ) {
        category(group)
        compatibleWith(MessengerTarget.COMPATIBILITY)
        dependsOn(settingsExtension, featureResources)
        execute {
            val selected = hooks.toSet().ifEmpty { setOf(key) }
            validateControls(discoveredControls, selected)
            val methods = selected.associateWith { hook ->
                discoveredControls.getValue(hook).map { original ->
                    mutableClassDefBy(original.definingClass).methods.single { it.hookId() == original.hookId() }
                }
            }
            injectControl(key, methods)
            applied = true
        }
    }
}

@Suppress("unused")
val hideInboxAdsPatch = controlPatch("ads", "Hide inbox ads", "Filters typed inbox ad items. Live ad removal still needs an affected-account check.", "Inbox")
@Suppress("unused")
val hidePeoplePatch = controlPatch("people", "Hide People You May Know", "Hides suggested people in chats and on the Notifications tab.", "Inbox", "people", "people_list_end", "people_jewel")
@Suppress("unused")
val hideFriendRequestsPatch = controlPatch("friend_requests", "Hide friend request cards", "Hides friend request cards inside the inbox.", "Inbox")
@Suppress("unused")
val hideGrowthPatch = controlPatch("growth", "Hide growth prompts", "Hides the inbox's add-more-people promotion unit.", "Inbox")
@Suppress("unused")
val hideInboxPromotionsPatch = controlPatch("inbox_promotions", "Hide inbox promotions", "Hides Messenger quick-promotion banners in the chat list.", "Inbox")
@Suppress("unused")
val hideStoriesPatch = controlPatch("stories", "Hide stories and notes", "Hides the horizontal tray above chats.", "Inbox")
@Suppress("unused")
val hideSubtabsPatch = controlPatch("subtabs", "Hide inbox tabs", "Hides the Home and Channels subtabs.", "Inbox")
@Suppress("unused")
val hideFacebookPatch = controlPatch("facebook", "Hide Facebook shortcuts", "Hides Facebook toolbar, profile and sharing shortcuts.", "Navigation")
@Suppress("unused")
val hideMetaAiPatch = controlPatch("meta_ai", "Hide Meta AI buttons", "Hides the floating button, toolbar button and AI menu entries. Search stays available.", "Navigation", "ai_menu", "ai_fab", "ai_toolbar")
@Suppress("unused")
val hideMomentsPatch = controlPatch("moments", "Hide Chat Moments", "Hides the Chat Moments entry in the menu.", "Navigation")
@Suppress("unused")
val hideReelsBadgePatch = controlPatch("reels_badge", "Hide Reels badge", "Hides the Reels notification badge.", "Navigation")
@Suppress("unused")
val hideAiStickersPatch = controlPatch("ai_stickers", "Hide AI sticker tools", "Hides the generated-sticker tab and AI sticker suggestions.", "Stickers")
@Suppress("unused")
val hideAvatarStickersPatch = controlPatch("avatar_stickers", "Hide avatar stickers", "Hides the avatar tab in the sticker keyboard.", "Stickers")
@Suppress("unused")
val hideChatPromotionsPatch = controlPatch("chat_promotions", "Hide chat promotions", "Hides Messenger quick-promotion banners inside conversations.", "Conversations")
@Suppress("unused")
val hideSuggestedRepliesPatch = controlPatch("suggested_replies", "Hide business reply suggestions", "Hides suggested replies in business conversations.", "Conversations")
@Suppress("unused")
val hideBusinessSuggestionsPatch = controlPatch("business_suggestions", "Hide business typing suggestions", "Hides business suggestions as you type.", "Conversations")
@Suppress("unused")
val hideEventPromptsPatch = controlPatch("event_prompts", "Hide event prompts", "Hides event quick-promotion prompts inside chats.", "Conversations")
@Suppress("unused")
val suppressTypingPatch = controlPatch("typing", "Hide typing indicator", "Suppresses your outgoing active-typing signal.", "Conversations")
@Suppress("unused")
val externalBrowserPatch = controlPatch("external_browser", "Open web links externally", "Uses Messenger's external-browser branch for HTTP and HTTPS links.", "Links and bubbles", "browser")
@Suppress("unused")
val enableBubblesPatch = controlPatch("bubbles", "Allow chat bubbles", "Removes the low-memory eligibility limit on Android 11 and newer.", "Links and bubbles")



