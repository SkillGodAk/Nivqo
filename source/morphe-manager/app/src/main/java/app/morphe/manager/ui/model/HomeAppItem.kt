package app.morphe.manager.ui.model

import android.content.pm.PackageInfo
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.morphe.manager.data.room.apps.installed.InstalledApp
import app.morphe.manager.domain.bundles.AppVersionStatus
import java.io.File

/**
 * One card on the home screen: either an app, or one of the installs it was cloned into.
 *
 * Cloning gives an app a second install under a package name of its own, so a card is identified
 * by [id] rather than by [packageName], which several cards of the same app share and which the
 * bundle data is keyed by.
 */
@Immutable
data class HomeAppItem(
    val id: String,
    val packageName: String,
    val displayName: String,
    val gradientColors: List<Color>,
    val installedApp: InstalledApp?,
    val packageInfo: PackageInfo?,
    val isPinnedByDefault: Boolean,
    val isInstalledOnDevice: Boolean,
    val isDeleted: Boolean,
    val isInstallStateNotPatched: Boolean,
    val isInstallStateUnknown: Boolean,
    val isInstallStatePending: Boolean,
    val savedApkFile: File?,
    val hasUpdate: Boolean,
    val versionStatus: AppVersionStatus?,
    val patchCount: Int,
    val isClone: Boolean
) {
    val hasSavedCopy: Boolean get() = savedApkFile != null

    /** The version this card is about: what the device reports, or what the record kept of it. */
    val version: String get() = packageInfo?.versionName ?: installedApp?.version.orEmpty()

    /**
     * Whether the install is settled enough for pending work on it to be worth surfacing. A record
     * in any other state keeps its flags but is described by the state it is in instead.
     */
    private val isSettledInstall: Boolean get() = !isDeleted &&
            !isInstallStateNotPatched &&
            !isInstallStateUnknown &&
            !isInstallStatePending

    val showsUpdateBadge: Boolean get() = hasUpdate && isSettledInstall

    /**
     * An install past everything the sources cover has already lost its patches, so the state it
     * is in says more than the version it is at, and only the rebuildable end is badged here.
     */
    val showsVersionBadge: Boolean get() = versionStatus?.isBehind == true && isSettledInstall

    /**
     * Whether the card carries a rebuild badge: newer patches, a newer supported app version, or
     * both. Only what the badge says: rebuilding at a version the sources still cover returns the
     * install as it went in, so queueing and sorting stay with [showsUpdateBadge] alone.
     */
    val showsRebuildBadge: Boolean get() = showsUpdateBadge || showsVersionBadge
}

/**
 * What a home screen card is about, before anything is read off the device to describe it.
 *
 * @param isClone Whether the card is about a copy of the app rather than about the app itself.
 */
data class HomeAppSlot(
    val id: String,
    val packageName: String,
    val installedApp: InstalledApp?,
    val isClone: Boolean
)

/**
 * The cards one app is shown as: the app itself, followed by every further install of it, ordered
 * by package name so the list does not move around between reads.
 *
 * A rename is not what gives a build a card of its own. Patches rename an app for reasons of
 * their own, and such a build is still the app's own install, so it belongs on the app's card,
 * which is where the user goes to rebuild it. The app keeps that card even once its only installs
 * are copies, because it is the only place another copy can be made from.
 */
fun homeAppSlots(packageName: String, records: List<InstalledApp>): List<HomeAppSlot> {
    // Two records can describe the app's own install, a mount and a renamed build side by side.
    // The card goes to the one answering to the app's name, and the other keeps a card of its own
    // rather than dropping out of reach of the actions that manage it
    val own = records.firstOrNull { !it.isClone && it.currentPackageName == packageName }
        ?: records.filterNot { it.isClone }.minByOrNull { it.currentPackageName }
    val separate = records.filter { it.currentPackageName != own?.currentPackageName }

    return buildList {
        add(HomeAppSlot(packageName, packageName, own, isClone = false))
        separate.sortedBy { it.currentPackageName }.forEach { record ->
            add(HomeAppSlot(record.currentPackageName, packageName, record, record.isClone))
        }
    }
}
