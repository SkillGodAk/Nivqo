/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.data.room.selection

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import app.morphe.manager.data.room.bundles.PatchBundleEntity

/**
 * A source the user ruled out for one app, while it stays switched on everywhere else.
 *
 * Only the exclusions are recorded. A source nobody ruled out has no row here, so one added later
 * is offered for every app until it is ruled out for one. Deleting a source takes its rows along.
 */
@Entity(
    tableName = "app_source_mutes",
    primaryKeys = ["patch_bundle", "package_name"],
    foreignKeys = [ForeignKey(
        PatchBundleEntity::class,
        parentColumns = ["uid"],
        childColumns = ["patch_bundle"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class AppSourceMute(
    @ColumnInfo(name = "patch_bundle") val patchBundle: Int,
    @ColumnInfo(name = "package_name") val packageName: String
)
