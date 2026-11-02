/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.data.room.selection

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SelectionSetDao {
    /**
     * Every set with how many patches it selects from each source, a row per set and source. A set
     * whose sources are all gone still has its row, with a null source.
     */
    @Query(
        "SELECT s.uid, s.package_name, s.name, p.patch_bundle, COUNT(p.patch_name) AS patch_count" +
                " FROM selection_sets s" +
                " LEFT JOIN selection_set_patches p ON p.selection_set = s.uid" +
                " GROUP BY s.uid, p.patch_bundle"
    )
    abstract fun getSummaryRows(): Flow<List<SelectionSetSummaryRow>>

    @Query("SELECT * FROM selection_sets")
    abstract suspend fun getAll(): List<SelectionSet>

    @Query("SELECT * FROM selection_sets WHERE uid = :setUid")
    abstract suspend fun get(setUid: Int): SelectionSet?

    @Query("SELECT * FROM selection_set_patches WHERE selection_set = :setUid")
    abstract suspend fun getPatches(setUid: Int): List<SelectionSetPatch>

    @Query("SELECT * FROM selection_set_options WHERE selection_set = :setUid")
    abstract suspend fun getOptions(setUid: Int): List<SelectionSetOption>

    @Query("DELETE FROM selection_sets WHERE uid = :setUid")
    abstract suspend fun delete(setUid: Int)

    @Query("DELETE FROM selection_sets")
    abstract suspend fun reset()

    @Query("DELETE FROM selection_sets WHERE package_name = :packageName AND name = :name")
    protected abstract suspend fun deleteNamed(packageName: String, name: String)

    @Query("DELETE FROM selection_sets WHERE package_name = :packageName AND name = :name AND uid != :keepUid")
    protected abstract suspend fun deleteNamedExcept(packageName: String, name: String, keepUid: Int)

    @Query("UPDATE selection_sets SET name = :name WHERE uid = :setUid")
    protected abstract suspend fun setName(setUid: Int, name: String)

    @Insert
    protected abstract suspend fun insertSet(set: SelectionSet)

    @Insert
    protected abstract suspend fun insertPatches(patches: List<SelectionSetPatch>)

    @Insert
    protected abstract suspend fun insertOptions(options: List<SelectionSetOption>)

    /** Names set [setUid] of [packageName] [name], in place of any other set the app has under it. */
    @Transaction
    open suspend fun rename(setUid: Int, packageName: String, name: String) {
        deleteNamedExcept(packageName, name, setUid)
        setName(setUid, name)
    }

    /** Stores [set] with what it holds, in place of any set the app already has under its name. */
    @Transaction
    open suspend fun save(set: SelectionSet, patches: List<SelectionSetPatch>, options: List<SelectionSetOption>) {
        deleteNamed(set.packageName, set.name)
        insertSet(set)
        if (patches.isNotEmpty()) insertPatches(patches)
        if (options.isNotEmpty()) insertOptions(options)
    }
}

/** One row of [SelectionSetDao.getSummaryRows]. */
data class SelectionSetSummaryRow(
    @ColumnInfo(name = "uid") val uid: Int,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "patch_bundle") val patchBundle: Int?,
    @ColumnInfo(name = "patch_count") val patchCount: Int
)
