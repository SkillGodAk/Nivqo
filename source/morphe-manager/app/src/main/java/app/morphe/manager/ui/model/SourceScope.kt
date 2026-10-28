/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.model

import app.morphe.manager.patcher.patch.PatchBundleInfo

/**
 * What a link asks to add of a source: all of it, the patches for one app, or the universal
 * patches alone. Asking for less than the whole source keeps its other apps off the home screen,
 * the ones it adds later included.
 */
sealed interface SourceScope {
    /** The apps the source keeps bringing, or null for every one of them. */
    val keptApps: Set<String>?

    /**
     * Whether [source] has what was asked for. One that does not is added whole, as nothing it
     * brings would be left otherwise.
     */
    fun isMetBy(source: PatchBundleInfo): Boolean

    data object Whole : SourceScope {
        override val keptApps: Set<String>? = null
        override fun isMetBy(source: PatchBundleInfo) = true
    }

    /** The app [packageName] alone, which the link calls [appName]. */
    data class App(val packageName: String, val appName: String?) : SourceScope {
        override val keptApps = setOf(packageName)
        override fun isMetBy(source: PatchBundleInfo) = packageName in source.listedApps()
    }

    /** The patches for any app alone, which name no app and so bring none to the home screen. */
    data object Universal : SourceScope {
        override val keptApps = emptySet<String>()
        override fun isMetBy(source: PatchBundleInfo) = source.patches.any { it.isUniversal }
    }

    companion object {
        /** What the patches site passes for its universal patches in place of a package. */
        private const val UNIVERSAL_APP = "__universal__"

        /** The scope a link names by its [app] parameter, which it calls [appName]. */
        fun fromLink(app: String?, appName: String?): SourceScope = when (app) {
            null -> Whole
            UNIVERSAL_APP -> Universal
            else -> App(app, appName)
        }
    }
}
