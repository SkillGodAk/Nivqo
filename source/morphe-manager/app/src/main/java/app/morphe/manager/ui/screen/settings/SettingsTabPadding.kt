/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import app.morphe.manager.ui.screen.shared.Defaults
import app.morphe.manager.ui.screen.shared.contentPadding
import app.morphe.manager.ui.screen.shared.rememberWindowSize

/**
 * Space a settings tab leaves under its last card. A navigation bar below the tab brings padding
 * of its own, so the screen showing one lowers this to keep the gap the size of every other.
 */
internal val LocalSettingsTabBottomPadding = staticCompositionLocalOf { Defaults.ContentPadding }

/** Padding every settings tab puts around its content. */
@Composable
internal fun settingsTabPadding(): PaddingValues {
    val horizontal = rememberWindowSize().contentPadding
    return PaddingValues(
        start = horizontal,
        top = Defaults.ContentPadding,
        end = horizontal,
        bottom = LocalSettingsTabBottomPadding.current
    )
}
