/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 *
 * Original hard forked code:
 * https://github.com/Jman-Github/Universal-ReVanced-Manager/blob/597b3173a004f5a9aae54326046dd7fd4c5b7777/app/src/main/java/app/revanced/manager/domain/repository/PatchSelectionRepository.kt
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.manager.domain.repository

import app.morphe.manager.data.room.AppDatabase
import app.morphe.manager.data.room.AppDatabase.Companion.generateUid
import app.morphe.manager.data.room.selection.PatchSelection
import app.morphe.manager.ui.model.configurationKey
import kotlinx.coroutines.flow.Flow

class PatchSelectionRepository(db: AppDatabase) {
    private val dao = db.selectionDao()

    private suspend fun getOrCreateSelection(bundleUid: Int, packageName: String) =
        dao.getSelectionId(bundleUid, packageName) ?: PatchSelection(
            uid = generateUid(),
            patchBundle = bundleUid,
            packageName = packageName
        ).also { dao.createSelection(it) }.uid

    /**
     * Get all selections for a package (across all bundles).
     * Returns: Map<BundleUid, Set<PatchNames>>.
     */
    suspend fun getAllSelectionsForPackage(packageName: String): Map<Int, Set<String>> =
        dao.getAllSelectionsForPackage(packageName)
            .mapValues { it.value.toSet() }
            .filterValues { it.isNotEmpty() }

    /**
     * The package a run for [originalPackageName] reads its saved configuration from, see
     * [configurationKey]. The rebuilt install is only looked up where it decides anything.
     */
    suspend fun configurationKeyFor(originalPackageName: String, repatchedPackageName: String?): String =
        configurationKey(
            originalPackageName = originalPackageName,
            repatchedPackageName = repatchedPackageName,
            repatchedHasOwnConfiguration = repatchedPackageName != null &&
                    repatchedPackageName != originalPackageName &&
                    getAllSelectionsForPackage(repatchedPackageName).isNotEmpty()
        )

    /**
     * Replace the selections for a package within [scope]. Bundles inside [scope] that had
     * selections but are absent from [selection] are cleared so no orphan rows remain, while
     * bundles outside it keep what they had: a disabled bundle is not part of any patching
     * session and must not lose its selection because of one.
     */
    suspend fun updateSelection(
        packageName: String,
        selection: Map<Int, Set<String>>,
        scope: Set<Int> = selection.keys
    ) {
        val previousBundles = dao.getAllSelectionsForPackage(packageName).keys
        val full = (previousBundles.intersect(scope) + selection.keys)
            .associateWith { selection[it] ?: emptySet() }
        dao.updateSelections(full.mapKeys { (sourceUid, _) ->
            getOrCreateSelection(sourceUid, packageName)
        })
    }

    /**
     * Get data about saved selections per bundle+package.
     * Returns: Map<PackageName, Map<BundleUid, PatchCount>>.
     */
    fun getSelectionsSummaryFlow(): Flow<Map<String, Map<Int, Int>>> {
        return dao.getSelectionsSummaryFlow()
    }

    /**
     * Reset selection for a specific package (all bundles)
     */
    suspend fun resetSelectionForPackage(packageName: String) {
        dao.resetForPackage(packageName)
    }

    /**
     * Reset selection for a specific package and bundle combination
     */
    suspend fun resetSelectionForPackageAndBundle(packageName: String, bundleUid: Int) {
        dao.resetForPackageAndBundle(packageName, bundleUid)
    }

    /**
     * Reset all selections
     */
    suspend fun reset() {
        dao.reset()
    }

    /** Export selection for a specific bundle and package. */
    suspend fun exportForPackageAndBundle(packageName: String, bundleUid: Int): List<String> =
        dao.exportSelectionForPackageAndBundle(packageName, bundleUid)

    /** Export all selections for a bundle. Returns Map<PackageName, List<PatchName>>. */
    suspend fun exportAllForBundle(bundleUid: Int): Map<String, List<String>> =
        dao.exportSelection(bundleUid)

    /** Get all bundle uids that have at least one saved selection. */
    suspend fun getAllBundleUids(): List<Int> =
        dao.getAllBundleUids()

    /** Export all selections for a bundle. */
    suspend fun export(bundleUid: Int): SerializedSelection = dao.exportSelection(bundleUid)

    /** Import selection for a specific bundle. */
    suspend fun import(bundleUid: Int, selection: SerializedSelection) {
        dao.resetForPatchBundle(bundleUid)
        dao.updateSelections(selection.entries.associate { (packageName, patches) ->
            getOrCreateSelection(bundleUid, packageName) to patches.toSet()
        })
    }

    /** Import selection for a specific package and bundle. */
    suspend fun importForPackageAndBundle(
        packageName: String,
        bundleUid: Int,
        patches: List<String>
    ) {
        val selectionId = getOrCreateSelection(bundleUid, packageName)
        dao.updateSelections(mapOf(selectionId to patches.toSet()))
    }


    /**
     * Save the complete set of patch names present in a bundle at patch time.
     * Called after the user confirms patching so that on the next open we can
     * distinguish genuinely new patches from patches the user simply deselected.
     */
    suspend fun saveSeenPatches(packageName: String, bundleUid: Int, patchNames: Set<String>) {
        dao.updateSeenPatches(packageName, bundleUid, patchNames)
    }

    /**
     * Get previously seen patch names for a bundle+package.
     * Returns null if no snapshot exists yet (first patching session).
     */
    suspend fun getSeenPatches(packageName: String, bundleUid: Int): Set<String>? {
        val result = dao.getSeenPatches(packageName, bundleUid)
        return if (result.isEmpty()) null else result.toSet()
    }
}

/** A [Map] of package name -> selected patches. */
typealias SerializedSelection = Map<String, List<String>>
