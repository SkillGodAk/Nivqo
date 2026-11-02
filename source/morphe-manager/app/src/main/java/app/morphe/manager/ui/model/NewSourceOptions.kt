/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * What is asked of sources as they are added: whether the app list of each opens once it has
 * loaded, and whether it starts on prerelease, which is best settled before its first download.
 */
@Parcelize
data class NewSourceOptions(
    val chooseApps: Boolean = false,
    val usePrerelease: Boolean = false
) : Parcelable
