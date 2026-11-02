/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.data.room.selection

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.morphe.manager.data.room.bundles.PatchBundleEntity
import app.morphe.manager.data.room.options.Option

/**
 * A selection the user kept under a name of their own for one app, every source of it at once, so
 * the app can be switched between ways of patching it without picking the patches again.
 *
 * Kept apart from the selection a run saves, which the next run overwrites. Names are unique per
 * app, so saving under a taken name replaces that set.
 */
@Entity(
    tableName = "selection_sets",
    indices = [Index(value = ["package_name", "name"], unique = true)]
)
data class SelectionSet(
    @PrimaryKey val uid: Int,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * One patch a [SelectionSet] selects from one source. A source the set has no rows for is one it
 * leaves unused, and deleting a source takes its rows along.
 */
@Entity(
    tableName = "selection_set_patches",
    primaryKeys = ["selection_set", "patch_bundle", "patch_name"],
    foreignKeys = [
        ForeignKey(
            SelectionSet::class,
            parentColumns = ["uid"],
            childColumns = ["selection_set"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            PatchBundleEntity::class,
            parentColumns = ["uid"],
            childColumns = ["patch_bundle"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["patch_bundle"])]
)
data class SelectionSetPatch(
    @ColumnInfo(name = "selection_set") val selectionSet: Int,
    @ColumnInfo(name = "patch_bundle") val patchBundle: Int,
    @ColumnInfo(name = "patch_name") val patchName: String
)

/** One option value a [SelectionSet] holds, stored the way the live options are. */
@Entity(
    tableName = "selection_set_options",
    primaryKeys = ["selection_set", "patch_bundle", "patch_name", "key"],
    foreignKeys = [
        ForeignKey(
            SelectionSet::class,
            parentColumns = ["uid"],
            childColumns = ["selection_set"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            PatchBundleEntity::class,
            parentColumns = ["uid"],
            childColumns = ["patch_bundle"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["patch_bundle"])]
)
data class SelectionSetOption(
    @ColumnInfo(name = "selection_set") val selectionSet: Int,
    @ColumnInfo(name = "patch_bundle") val patchBundle: Int,
    @ColumnInfo(name = "patch_name") val patchName: String,
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: Option.SerializedValue
)
