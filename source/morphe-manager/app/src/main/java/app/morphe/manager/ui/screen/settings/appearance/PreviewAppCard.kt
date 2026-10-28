/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.appearance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.morphe.manager.R
import app.morphe.manager.patcher.patch.BundleAppMetadata
import app.morphe.manager.ui.screen.home.AppCardContent
import app.morphe.manager.ui.screen.home.AppCardLayout
import app.morphe.manager.ui.screen.shared.rememberAppMetadata
import app.morphe.manager.util.KnownApps

/**
 * App the preview card stands for: YouTube where a source colors it, otherwise the first app a
 * source does. The home screen opens each card on its app's color, so a preview without one would
 * not show what [app.morphe.manager.util.AppCardColorMode.DEFAULT] looks like.
 */
@Composable
internal fun rememberPreviewApp(): BundleAppMetadata? {
    val metadata = rememberAppMetadata()
    return remember(metadata) {
        metadata[KnownApps.YOUTUBE]?.takeIf { it.gradientColors != null }
            ?: metadata.values.firstOrNull { it.gradientColors != null }
    }
}

/**
 * A home screen card for [app], drawn by the same layout as the real ones so a preview of the
 * appearance settings matches the home screen exactly. It stands for no app the user can open
 * from here, so the preview holding it lays it out of reach, see
 * [app.morphe.manager.ui.screen.shared.DisplayOnly].
 *
 * @param app App the card stands for, or null to show Morphe.
 * @param appColors The colors its bundle declares, which the card's color mode builds on.
 */
@Composable
internal fun PreviewAppCard(app: BundleAppMetadata?, appColors: List<Color>) {
    AppCardLayout(
        gradientColors = appColors,
        onClick = {}
    ) {
        AppCardContent(
            packageName = app?.packageName,
            packageInfo = null,
            displayName = app?.displayName ?: stringResource(R.string.app_name),
            subtitle = stringResource(R.string.home_not_patched_yet),
            gradientColors = appColors
        )
    }
}
