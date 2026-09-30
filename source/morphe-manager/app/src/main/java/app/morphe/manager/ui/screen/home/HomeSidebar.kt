/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.domain.manager.HomeAppSortMode
import app.morphe.manager.ui.screen.shared.ActionGlassButton
import app.morphe.manager.ui.screen.shared.BottomActionTone
import app.morphe.manager.ui.screen.shared.Defaults

/**
 * Landscape sidebar panel: the buttons of [HomeBottomActionBar] stacked and centered vertically,
 * in the same glass style and states, labels always shown since the sidebar has the room.
 */
@Composable
internal fun HomeSidebarPanel(
    showSearchButton: Boolean,
    searchActive: Boolean,
    isExpertModeEnabled: Boolean,
    showSortButton: Boolean,
    sortMode: HomeAppSortMode,
    filterMode: HomeAppFilterMode,
    onSearchClick: () -> Unit,
    onSortClick: () -> Unit,
    onBundlesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSourcesPositioned: ((Rect) -> Unit)? = null,
    onSettingsPositioned: ((Rect) -> Unit)? = null
) {
    val settingsLabel = stringResource(R.string.settings)
    val expertModeLabel = stringResource(R.string.settings_advanced_expert_mode)

    Column(
        modifier = modifier
            .width(220.dp)
            .fillMaxHeight()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Defaults.ItemSpacing, Alignment.CenterVertically)
    ) {
        if (showSearchButton) {
            ActionGlassButton(
                onClick = onSearchClick,
                icon = if (searchActive) Icons.Outlined.SearchOff else Icons.Outlined.Search,
                text = stringResource(R.string.home_search_apps),
                showLabel = true,
                stateDescription = stringResource(if (searchActive) R.string.expanded else R.string.collapsed),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (showSortButton) {
            ActionGlassButton(
                onClick = onSortClick,
                icon = Icons.AutoMirrored.Outlined.Sort,
                text = stringResource(R.string.sort),
                showLabel = true,
                tone = if (filterMode.isActive) BottomActionTone.Highlight else BottomActionTone.Neutral,
                stateDescription = homeAppListOptionsStateDescription(sortMode, filterMode),
                modifier = Modifier.fillMaxWidth()
            )
        }
        ActionGlassButton(
            onClick = onBundlesClick,
            icon = Icons.Outlined.Source,
            text = stringResource(R.string.sources),
            showLabel = true,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onSourcesPositioned != null) Modifier.onGloballyPositioned { coords ->
                        onSourcesPositioned(coords.boundsInWindow())
                    } else Modifier
                )
        )
        ActionGlassButton(
            onClick = onSettingsClick,
            icon = if (isExpertModeEnabled) Icons.Outlined.Engineering else Icons.Outlined.Settings,
            text = settingsLabel,
            showLabel = true,
            contentDescription = if (isExpertModeEnabled) "$settingsLabel, $expertModeLabel" else null,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onSettingsPositioned != null) Modifier.onGloballyPositioned { coords ->
                        onSettingsPositioned(coords.boundsInWindow())
                    } else Modifier
                )
        )
    }
}
