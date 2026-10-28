/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.appearance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import app.morphe.manager.R
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.theme.*
import app.morphe.manager.util.AppCardColorDefaults
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*

/**
 * The display scale of the interface, picked on a slider over a preview drawn at the picked scale
 * and applied with a button of its own, since applying it recreates the activity.
 */
@Composable
fun UiScaleSetting(
    currentScale: Float,
    onApply: (Float) -> Unit
) {
    val appliedScale = currentScale.coerceToUiScale()
    val sliderState = remember(appliedScale) {
        SliderState(value = appliedScale, trackRange = UI_SCALE_MIN..UI_SCALE_MAX)
    }
    val selectedScale = sliderState.value
    val previewing = selectedScale != appliedScale
    // Held on to so the preview keeps its scale while it folds away, rather than snapping back
    // to the applied one on the way out
    var previewScale by remember { mutableFloatStateOf(selectedScale) }
    if (previewing) previewScale = selectedScale

    SettingsSliderCard(
        value = "${selectedScale.toUiScalePercent()}%",
        title = stringResource(R.string.settings_appearance_ui_scale),
        rangeStart = "${UI_SCALE_MIN.toUiScalePercent()}%",
        rangeEnd = "${UI_SCALE_MAX.toUiScalePercent()}%",
        slider = {
            Slider(
                state = sliderState,
                // Snapped here rather than on read, so the handle rests on the stops the scale has
                onValueChange = { sliderState.value = it.coerceToUiScale() },
                modifier = Modifier.fillMaxWidth()
            )
        },
        footer = {
            // Under the slider, so the preview growing with the scale never moves the handle out
            // from under the finger. Shown only once the pick moves off the applied scale, which
            // the whole app around it already shows
            FoldingVisibility(visible = previewing) {
                Column {
                    UiScalePreview(
                        relativeScale = previewScale / appliedScale,
                        modifier = Modifier.padding(top = Defaults.SpaceSmall)
                    )
                    // Reset sits here rather than beside the slider, where showing up would narrow
                    // the track
                    AppDialogButtonRow(
                        primaryText = stringResource(R.string.apply),
                        onPrimaryClick = { onApply(selectedScale) },
                        secondaryText = stringResource(R.string.reset),
                        onSecondaryClick = { sliderState.value = UI_SCALE_DEFAULT },
                        modifier = Modifier.padding(top = Defaults.SpaceSmall)
                    )
                }
            }
        }
    )
}

/**
 * A home screen card and a settings row drawn at [relativeScale] of the scale already applied,
 * taking up as much room as they do at that scale.
 *
 * Only shown: touch and accessibility pass it by, as it stands for settings found elsewhere.
 */
@Composable
private fun UiScalePreview(relativeScale: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val previewDensity = remember(density, relativeScale) { density.scaledBy(relativeScale) }
    val app = rememberPreviewApp()

    DisplayOnly(modifier = modifier.fillMaxWidth()) {
        CompositionLocalProvider(
            LocalDensity provides previewDensity,
            // The sample row is not a setting of this page, so a search must not count or hide it
            LocalSettingsSearch provides null
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)) {
                PreviewAppCard(
                    app = app,
                    appColors = app?.gradientColors ?: AppCardColorDefaults.defaultGradientColors
                )
                SettingsSwitchItem(
                    checked = true,
                    onToggle = {},
                    icon = MaterialSymbols.Rounded.Border_style,
                    title = stringResource(R.string.settings_appearance_outlines),
                    subtitle = stringResource(R.string.settings_appearance_outlines_description),
                    showBorder = true
                )
            }
        }
    }
}
