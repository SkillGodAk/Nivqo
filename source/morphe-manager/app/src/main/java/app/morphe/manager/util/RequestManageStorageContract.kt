/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 *
 * Original hard forked code:
 * https://github.com/Jman-Github/Universal-ReVanced-Manager/blob/597b3173a004f5a9aae54326046dd7fd4c5b7777/app/src/main/java/app/revanced/manager/util/RequestManageStorageContract.kt
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.manager.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.R)
class RequestManageStorageContract(private val forceLaunch: Boolean = false) : ActivityResultContract<String, Boolean>() {
    override fun createIntent(context: Context, input: String) = context.allFilesAccessSettingsIntent()

    override fun getSynchronousResult(context: Context, input: String): SynchronousResult<Boolean>? {
        return if (!forceLaunch && Environment.isExternalStorageManager()) SynchronousResult(true) else null
    }

    override fun parseResult(resultCode: Int, intent: Intent?) = Environment.isExternalStorageManager()
}
