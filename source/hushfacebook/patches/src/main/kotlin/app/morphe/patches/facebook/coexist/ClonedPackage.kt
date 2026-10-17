/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.facebook.chats.jumpTargets
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.patchLog
import app.morphe.util.readsAfter
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element

/*
 * What Morphe's Clone app patch leaves for this bundle to finish, so a renamed copy of Facebook
 * installs and starts beside the Facebook it was cloned from (#16).
 *
 * Clone app renames the package in its finalize block. With Update permissions on it renames each
 * permission Facebook declares, com.facebook.katana.X to <package>.X and any other name to
 * <package>_<name>, and the first <uses-permission> of each, but not the components that require
 * one: they went on naming permissions only the stock app declares. With it off, which is its
 * default, the declarations keep the stock names, and Android refuses the clone beside a Facebook
 * signed with another key (INSTALL_FAILED_DUPLICATE_PERMISSION). With Update providers on it
 * renames the provider authorities the same way. With it off they stay the stock app's, and Android
 * refuses a second app claiming one (INSTALL_FAILED_CONFLICTING_PROVIDER). And Facebook's code
 * spells some of its own authorities out, content://com.facebook.katana.ClientMessagePushDedupInfoProvider/mutestatus
 * among them, so a clone started up by deleting through the stock app's provider, which turned it
 * away, and with Update permissions on Android refused it before that.
 *
 * Morphe executes the patches it's given sorted by name, each after its dependencies, and runs the
 * finalize blocks in the reverse order, so this one's finalize sees Clone app's work only when it
 * executed before "Clone app". Every patch depends on the settings patch, which depends on this,
 * and the default selection has patches whose names sort before "Clone app" (ClonedPackageOrderTest
 * holds both). A selection without any of them leaves the manifest as Clone app wrote it. The code
 * half doesn't depend on the order: the extension reads the package at run time.
 *
 * There's no way to make this run after "Clone app" whatever the reader picks: Morphe sorts and
 * finalizes by what got selected, "Clone app" comes from a different bundle this one can't depend
 * on, and a patch has no way to ask what else was selected. So a selection holding only patches
 * whose names sort after "Clone app" (ClonedPackageOrderTest.aSelectionOfOnlyLaterNamedPatchesRunsCloneAppFirst
 * shows one) leaves [followRenamedPackage] running before the rename, with nothing to follow yet.
 * It can't tell that apart from an ordinary install either, so it says what to add at fine level
 * instead of warning on every build that was never a clone.
 */

/** The call each of Facebook's own authority literals goes through, into the register it was loaded into. */
internal const val AUTHORITY_CALL =
    "$EXTENSION_PACKAGE/coexist/OwnAuthorities;->name(Ljava/lang/String;)Ljava/lang/String;"

internal const val CONTENT = "content://"

private fun Document.elements(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
}

/** Facebook's own provider authorities: every entry of a provider's list that's under [packageName]. */
internal fun Document.ownAuthorities(packageName: String): Set<String> = elements("provider")
    .flatMap { it.getAttribute("android:authorities").split(';') }
    .map { it.trim() }
    .filter { it.startsWith("$packageName.") }
    .toSet()

/** Facebook's own permissions: every one it declares under [packageName]. */
internal fun Document.ownPermissions(packageName: String): Set<String> = elements("permission")
    .map { it.getAttribute("android:name") }
    .filter { it.startsWith("$packageName.") }
    .toSet()

/**
 * What following a renamed package moved: mentions of a permission, authorities, and declarations
 * Clone app left under the stock package.
 */
internal data class Followed(val permissions: Int, val authorities: Int, val declarations: Int = 0)

/**
 * After something renamed the package away from [originalPackage]: moves every permission still
 * declared under one of [stockPermissions] under the new package, which is where Clone app's Update
 * permissions would have put it, points every mention of a permission this manifest no longer
 * declares at the renamed declaration, and moves every authority still in [stockAuthorities] under
 * the new package, which is where its Update providers would have put it. Answers what it moved, or
 * null when the package is still [originalPackage], and then it changes nothing but a fine message,
 * since this could be an ordinary install as easily as a clone whose "Clone app" hasn't finalized yet.
 *
 * Facebook's five own permissions are signature permissions, so a clone that still declares one
 * under the stock name can't install beside Facebook signed with another key, Meta's own included
 * (INSTALL_FAILED_DUPLICATE_PERMISSION, #60). Its code names two of them after the running package
 * (the cross-process broadcast manager's and AndroidX's not-exported receiver's), so in a clone
 * those only work once the declarations moved too.
 */
internal fun Document.followRenamedPackage(
    originalPackage: String,
    stockAuthorities: Set<String>,
    stockPermissions: Set<String>,
): Followed? {
    val renamedTo = documentElement.getAttribute("package")
    if (renamedTo.isEmpty() || renamedTo == originalPackage) {
        patchLog.fine(
            "The package is still $originalPackage, so there's nothing to follow yet. If you're patching " +
                "with Clone app and the clone still doesn't start beside the app it came from, also select a " +
                "patch whose name sorts before \"Clone app\", such as Block ad telemetry, which is on by " +
                "default: Morphe finalizes in reverse name order, and this step has to run after Clone app's " +
                "to see the renamed manifest.",
        )
        return null
    }

    fun movedUnder(name: String) = renamedTo + name.removePrefix(originalPackage)
    var declarations = 0
    for (declaration in elements("permission")) {
        val name = declaration.getAttribute("android:name")
        if (name !in stockPermissions) continue
        declaration.setAttribute("android:name", movedUnder(name))
        declarations++
    }

    val declared = elements("permission").map { it.getAttribute("android:name") }.toSet()
    fun cloneDeclarationOf(name: String): String? = listOfNotNull(
        movedUnder(name).takeIf { name.startsWith("$originalPackage.") },
        "${renamedTo}_$name",
    ).firstOrNull { it in declared }

    var permissions = 0
    for (element in elements("*")) {
        val attributes = element.attributes
        for (at in 0 until attributes.length) {
            val attribute = attributes.item(at) as? Attr ?: continue
            if (element.tagName == "permission" && attribute.nodeName == "android:name") continue
            if (attribute.value in declared) continue
            val clone = cloneDeclarationOf(attribute.value) ?: continue
            attribute.value = clone
            permissions++
        }
    }

    var authorities = 0
    for (provider in elements("provider")) {
        val entries = provider.getAttribute("android:authorities").split(';')
        val moved = entries.map { entry ->
            val authority = entry.trim()
            if (authority !in stockAuthorities) return@map entry
            authorities++
            entry.replace(authority, movedUnder(authority))
        }
        if (moved != entries) provider.setAttribute("android:authorities", moved.joinToString(";"))
    }
    return Followed(permissions, authorities, declarations)
}

/** The authority [this] names when it's one of [authorities] or a content:// address on one, else null. */
internal fun String.ownAuthority(authorities: Set<String>): String? {
    if (this in authorities) return this
    if (!startsWith(CONTENT)) return null
    return removePrefix(CONTENT).takeWhile { it != '/' && it != '?' && it != '#' }.takeIf { it in authorities }
}

/**
 * Hands every literal of Facebook's code naming one of its own [authorities] to the extension,
 * which answers the running app's. Answers how many it handed over. Every one is under
 * [packageName], which the string index finds with a prefix, and the extension is left alone.
 */
internal fun BytecodePatchContext.routeOwnAuthorities(packageName: String, authorities: Set<String>): Int {
    val owners = listOf("$packageName.", "$CONTENT$packageName.")
        .flatMap { classDefByStrings(it, StringComparisonType.STARTS_WITH) }
        .map { it.type }
        .filterNot { it.startsWith(EXTENSION_ROOT) }
        .distinct()
    return owners.sumOf { type ->
        mutableClassDefBy(type).methods
            .filter { method -> method.implementation?.instructions?.any { it.loadedString()?.ownAuthority(authorities) != null } == true }
            .sumOf { it.routeLiterals(AUTHORITY_CALL, "Clone support", "an authority") { literal -> literal.ownAuthority(authorities) != null } }
    }
}

/*
 * Facebook's rich notification templates build their RemoteViews with the package spelled out,
 * new RemoteViews("com.facebook.katana", layout), 14 times in 582. RemoteViews takes its layouts
 * from that package, so in a clone a notification either named an app that isn't installed, which
 * throws, or the Facebook it was cloned from, whose layout ids aren't the clone's, and Android
 * refused it as a bad notification and closed the clone (#36). Each of those loads is handed to the
 * extension, which answers the running package.
 */

/** The call Facebook's package name goes through where it's loaded only to build RemoteViews. */
internal const val PACKAGE_CALL =
    "$EXTENSION_PACKAGE/coexist/OwnAuthorities;->packageName(Ljava/lang/String;)Ljava/lang/String;"

internal const val REMOTE_VIEWS_INIT = "Landroid/widget/RemoteViews;-><init>(Ljava/lang/String;I)V"

/** Whether [instruction] builds a RemoteViews from the package name in [register] and reads it for nothing else. */
private fun buildsRemoteViewsOf(instruction: Instruction, register: Int): Boolean {
    if (instruction.opcode != Opcode.INVOKE_DIRECT) return false
    if (((instruction as ReferenceInstruction).reference as? MethodReference)?.toString() != REMOTE_VIEWS_INIT) return false
    val call = instruction as FiveRegisterInstruction
    return call.registerD == register && call.registerC != register && call.registerE != register
}

/**
 * Where [this] loads [packageName] as a literal only to build RemoteViews with it: the index of each
 * const-string whose value reaches nothing but RemoteViews(String, int)'s package before its register
 * is written again. A load something jumps in right after is left out, since the jump would pass
 * the call that goes there, and so is a value anything else reads.
 */
internal fun Method.remoteViewsPackageLoads(packageName: String): List<Int> =
    packageLoadsReadOnlyBy(packageName, ::buildsRemoteViewsOf)

/*
 * The rest of the bare package name (#36 leftovers). Where it means this app, a clone has to get
 * its own: Firebase's notification code (FirebaseMessagingService, a kept name) sets the intent a
 * notification's tap sends to the package, launches the package when that finds nothing, and reads
 * the package's meta-data for the notification's icon, colour and channel, all of them
 * context.getPackageName() in Google's source that Facebook's build spelled out; and a chat
 * notification's intent is set to the package. In a clone those reach the stock app or nothing.
 *
 * Left alone, because there the name means Meta's Facebook app: getPackageInfo reads of its
 * version or signatures (the in-app browser's FB4A version check among them), String.equals of a
 * caller's or a target's package against it, the launch of Facebook after a check that it or
 * its in-house build is installed, and Messenger's warm-start hint (ThreadSettingsActivity), which
 * checks that Facebook is installed and then sends its WarmStartBroadcastReceiver a broadcast. A
 * load that anything else reads, such as that install check or a ComponentName, stays as it is.
 */

internal const val SET_PACKAGE = "Landroid/content/Intent;->setPackage(Ljava/lang/String;)Landroid/content/Intent;"
internal const val LAUNCH_INTENT =
    "Landroid/content/pm/PackageManager;->getLaunchIntentForPackage(Ljava/lang/String;)Landroid/content/Intent;"
internal const val APPLICATION_INFO =
    "Landroid/content/pm/PackageManager;->getApplicationInfo(Ljava/lang/String;I)Landroid/content/pm/ApplicationInfo;"
internal const val FIREBASE_MESSAGING = "Lcom/google/firebase/messaging/FirebaseMessagingService;"

/**
 * Whether [instruction] hands the package name in [register] to a call that means this app, and
 * reads it for nothing else: an intent's setPackage anywhere, and in Firebase's notification code
 * ([firebase]) a launch intent or an ApplicationInfo for it as well.
 */
private fun callsOwnPackage(instruction: Instruction, register: Int, firebase: Boolean): Boolean {
    if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return false
    val called = ((instruction as ReferenceInstruction).reference as? MethodReference)?.toString() ?: return false
    if (called != SET_PACKAGE && !(firebase && (called == LAUNCH_INTENT || called == APPLICATION_INFO))) return false
    val call = instruction as FiveRegisterInstruction
    return call.registerD == register && call.registerC != register && (call.registerCount < 3 || call.registerE != register)
}

/** Where [this] loads [packageName] only to name this app to Android ([callsOwnPackage]). */
internal fun Method.ownPackageCallLoads(packageName: String): List<Int> {
    val firebase = definingClass == FIREBASE_MESSAGING
    return packageLoadsReadOnlyBy(packageName) { instruction, register -> callsOwnPackage(instruction, register, firebase) }
}

/**
 * The index of each const-string of [packageName] in [this] whose value only [reads] reads before
 * its register is written again. A load something jumps in right after is left out, since the jump
 * would pass the call that goes there.
 */
private fun Method.packageLoadsReadOnlyBy(packageName: String, reads: (Instruction, Int) -> Boolean): List<Int> {
    val code = implementation?.instructions?.toList() ?: return emptyList()
    val loads = code.indices.filter { code[it].loadedString() == packageName }
    if (loads.isEmpty()) return emptyList()
    val targets = jumpTargets(code)
    return loads.filter { at ->
        val register = (code[at] as OneRegisterInstruction).registerA
        if (at + 1 in targets || register > 255) return@filter false
        val readers = readsAfter(at, register)
        readers.isNotEmpty() && readers.all { reads(code[it], register) }
    }
}

/**
 * Hands every load of [packageName] that only builds RemoteViews to the extension, which answers
 * the running app's package. Answers how many it handed over. The extension is left alone.
 */
internal fun BytecodePatchContext.routeRemoteViewsPackage(packageName: String): Int =
    routePackageLoads(packageName) { it.remoteViewsPackageLoads(packageName) }

/**
 * Hands every load of [packageName] that only names this app to Android ([ownPackageCallLoads]) to
 * the extension, which answers the running app's package. Answers how many it handed over.
 */
internal fun BytecodePatchContext.routeOwnPackageCalls(packageName: String): Int =
    routePackageLoads(packageName) { it.ownPackageCallLoads(packageName) }

/** Hands each load [loadsOf] picks out to the extension, right after it. The extension is left alone. */
private fun BytecodePatchContext.routePackageLoads(packageName: String, loadsOf: (Method) -> List<Int>): Int {
    val owners = classDefByStrings(packageName, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_ROOT) }
        .filter { owner -> owner.methods.any { loadsOf(it).isNotEmpty() } }
        .map { it.type }
        .distinct()
    return owners.sumOf { type ->
        mutableClassDefBy(type).methods.sumOf { method ->
            val loads = loadsOf(method)
            loads.asReversed().forEach { at ->
                val register = method.getInstruction<OneRegisterInstruction>(at).registerA
                method.addInstructions(
                    at + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $PACKAGE_CALL
                        move-result-object v$register
                    """,
                )
            }
            loads.size
        }
    }
}

/*
 * Facebook's encrypted chat backup (MibEbAutoRestoreHandler) builds a helper whose constructor
 * looks the running package up among Messenger's and Facebook's names (com.facebook.orca, its debug
 * build, com.facebook.katana and com.facebook.wakizashi) to pick its preferences file, and throws
 * NoSuchElementException when none matches. A clone's package matches none, so it crashed as soon as
 * chats opened (#113). The package name each such lookup reads is handed to the extension, which
 * answers Facebook's in a clone. Only methods that also name Messenger's debug build count, which in
 * 582 is that constructor alone (the other method naming it is a table that reads no package).
 */

/** The call the running package goes through where Facebook looks itself up among Meta's apps. */
internal const val AS_FACEBOOK_CALL =
    "$EXTENSION_PACKAGE/coexist/OwnAuthorities;->asFacebook(Ljava/lang/String;)Ljava/lang/String;"

internal const val GET_PACKAGE_NAME = "Landroid/content/Context;->getPackageName()Ljava/lang/String;"

/** The name only Meta's own app lookups carry: Messenger's in-house build. */
internal const val MESSENGER_DEBUG = "com.facebook.orca.debug"

/**
 * Where [this] looks the running package up among Meta's apps: for each getPackageName() whose
 * result it moves into a register, the index right after that move, which is where the call goes.
 * A method that doesn't name [MESSENGER_DEBUG] has none, and a spot something jumps onto is left out,
 * since the jump would pass the call.
 */
internal fun Method.metaAppLookupReads(): List<Int> {
    val code = implementation?.instructions?.toList() ?: return emptyList()
    if (code.none { it.loadedString() == MESSENGER_DEBUG }) return emptyList()
    val targets = jumpTargets(code)
    return code.indices.filter { at ->
        val call = code[at]
        val moved = code.getOrNull(at + 1) ?: return@filter false
        call.opcode == Opcode.INVOKE_VIRTUAL &&
            ((call as ReferenceInstruction).reference as? MethodReference)?.toString() == GET_PACKAGE_NAME &&
            moved.opcode == Opcode.MOVE_RESULT_OBJECT &&
            (moved as OneRegisterInstruction).registerA <= 255 &&
            at + 2 !in targets
    }.map { it + 2 }
}

/**
 * Hands the running package to the extension wherever Facebook looks itself up among Meta's apps
 * ([metaAppLookupReads]). Answers how many it handed over. The extension is left alone.
 */
internal fun BytecodePatchContext.routeMetaAppLookups(): Int {
    val owners = classDefByStrings(MESSENGER_DEBUG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_ROOT) }
        .filter { owner -> owner.methods.any { it.metaAppLookupReads().isNotEmpty() } }
        .map { it.type }
        .distinct()
    return owners.sumOf { type ->
        mutableClassDefBy(type).methods.sumOf { method ->
            val reads = method.metaAppLookupReads()
            reads.asReversed().forEach { at ->
                val register = method.getInstruction<OneRegisterInstruction>(at - 1).registerA
                method.addInstructions(
                    at,
                    """
                        invoke-static/range { v$register .. v$register }, $AS_FACEBOOK_CALL
                        move-result-object v$register
                    """,
                )
            }
            reads.size
        }
    }
}

/** The stock app's own authorities and permissions, read before anything renames the package. */
private var stockAuthorities: Set<String> = emptySet()
private var stockPermissions: Set<String> = emptySet()

private val clonedPackageManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use {
            stockAuthorities = it.ownAuthorities(packageMetadata.packageName)
            stockPermissions = it.ownPermissions(packageMetadata.packageName)
        }
    }

    finalize {
        val followed = document("AndroidManifest.xml").use {
            it.followRenamedPackage(packageMetadata.packageName, stockAuthorities, stockPermissions)
        } ?: return@finalize
        patchLog.info(
            "The package was renamed, so ${followed.declarations} permission declaration(s) still under the old name " +
                "moved under the new one, ${followed.permissions} mention(s) of a permission now name the renamed " +
                "declaration, and ${followed.authorities} provider authorities moved under the new name.",
        )
    }
}

/**
 * Makes a copy renamed with Morphe's Clone app its own app: its manifest names only permissions it
 * declares and authorities it owns, and its code reaches its own providers. On an install nobody
 * renamed, the manifest is left as it was and the extension hands every authority back unchanged.
 */
internal val clonedPackagePatch = bytecodePatch {
    dependsOn(clonedPackageManifestPatch, facebookExtensionPatch)

    execute {
        routeOwnAuthorities(packageMetadata.packageName, stockAuthorities)
        routeRemoteViewsPackage(packageMetadata.packageName)
        routeOwnPackageCalls(packageMetadata.packageName)
        if (routeMetaAppLookups() == 0) {
            patchLog.warning(
                "Found no place where Facebook looks itself up among Meta's apps, so a copy made with Clone app " +
                    "may still close when its chats open.",
            )
        }
    }
}
