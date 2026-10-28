/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.ui.screen.shared.colorpicker.*
import app.morphe.manager.util.toColorOrNull
import app.morphe.manager.util.toHexString
import app.morphe.manager.util.toHsv
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*

/**
 * A choice offered beside a custom color, for colors that can follow a value computed elsewhere.
 * While it is picked the picker returns [token] instead of a hex value and the manual controls
 * fold away, because there is nothing to pick.
 *
 * @param icon            Marks the choice among the picker's modes.
 * @param previewColor    Color the token currently resolves to, used to seed the manual controls
 *   when the custom color is picked again.
 * @param previewGradient Shown instead of [previewColor] when the token stands for a value that
 *   varies rather than a single color; needs at least two colors to render.
 */
data class ColorPickerToggle(
    val label: String,
    val description: String,
    val token: String,
    val previewColor: Color,
    val previewGradient: List<Color> = emptyList(),
    val icon: ImageVector = MaterialSymbols.Rounded.Apps
)

/**
 * Color picker dialog for custom color selection.
 *
 * Works in hue, saturation and value rather than in red, green and blue: those are the axes a
 * color is actually chosen along, and two of them fit one panel. Hex stays as the way to carry a
 * color in and out, and as the way to type an exact one.
 *
 * @param presets Offered above the panel for reaching a common color in one tap. Pass an empty
 *   list where a palette would only be noise.
 */
@Composable
fun DialogHostScope.ColorPickerDialog(
    title: String,
    currentColor: String,
    onColorSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    toggle: ColorPickerToggle? = null,
    presets: List<Color> = THEME_PRESET_COLORS
) {
    val initialToggle = toggle?.takeIf {
        currentColor.trim().equals(it.token, ignoreCase = true)
    }

    var useToggle by rememberSaveable(currentColor, toggle?.token) { mutableStateOf(initialToggle != null) }

    // A color following the toggle has no hex of its own, so the manual controls open on the
    // value it currently resolves to instead of on black
    val initial = remember(currentColor, toggle?.previewColor) {
        initialToggle?.previewColor ?: currentColor.toColorOrNull() ?: Color.Black
    }
    // What the comparison opens on, a following color showing as the gradient it stands for
    val initialFill = remember(initial, initialToggle) {
        initialToggle?.previewGradient?.takeIf { it.size > 1 } ?: listOf(initial)
    }

    // Saved rather than merely remembered, a color half chosen being worth more than the one it
    // started from. Rotating still closes it, the settings tabs being rebuilt from a different
    // container in each orientation, which no state of this dialog's own can survive
    var hsv by rememberSaveable(stateSaver = HsvColor.Saver) {
        mutableStateOf(initial.toHsv().let { HsvColor(it.first, it.second, it.third) })
    }
    var hexInput by rememberSaveable { mutableStateOf(initial.toHexString()) }
    var isHexError by rememberSaveable { mutableStateOf(false) }

    /** Moving on the panel or the strip is what the hex readout follows, never the other way. */
    fun moveTo(updated: HsvColor) {
        hsv = updated
        hexInput = updated.color.toHexString()
        isHexError = false
    }

    val activeToggle = toggle?.takeIf { useToggle }
    val enabled = activeToggle == null

    AppDialog(
        onDismissRequest = onDismiss,
        title = title,
        footer = {
            AppDialogButtonRow(
                primaryText = stringResource(R.string.save),
                onPrimaryClick = { onColorSelected(activeToggle?.token ?: hexInput) },
                secondaryText = stringResource(android.R.string.cancel),
                onSecondaryClick = onDismiss
            )
        }
    ) {
        val landscape = isLandscape()

        val panel: @Composable () -> Unit = {
            SaturationValuePanel(
                hsv = hsv,
                onChange = { saturation, value -> moveTo(hsv.copy(saturation = saturation, value = value)) },
                // Landscape gives it the whole column, which is still less height than portrait
                height = if (landscape) 150.dp else 200.dp,
                contentDescription = stringResource(R.string.color_picker_shade)
            )
        }

        val strip: @Composable () -> Unit = {
            HueSlider(
                hue = hsv.hue,
                onChange = { moveTo(hsv.copy(hue = it)) },
                contentDescription = stringResource(R.string.color_picker_hue)
            )
        }

        val swatches: @Composable () -> Unit = {
            if (presets.isNotEmpty()) {
                ColorPresetGrid(
                    colors = presets,
                    selected = hsv.color,
                    onSelect = { preset ->
                        val (hue, saturation, value) = preset.toHsv()
                        moveTo(HsvColor(hue, saturation, value))
                    },
                    columns = PICKER_PRESET_COLUMNS
                )
            }
        }

        val hexField: @Composable () -> Unit = {
            AppDialogTextField(
                value = hexInput,
                onValueChange = { input ->
                    hexInput = input
                    val parsed = input.toColorOrNull()
                    if (parsed != null) {
                        // A gray types as hue 0, which would swing the panel back to red, so a
                        // color that carries no hue of its own keeps the one already on screen
                        val (hue, saturation, value) = parsed.toHsv()
                        hsv = HsvColor(if (saturation == 0f) hsv.hue else hue, saturation, value)
                        isHexError = false
                    } else {
                        isHexError = input.isNotEmpty() && !input.startsWith("@")
                    }
                },
                placeholder = { Text("#RRGGBB") },
                isError = isHexError
            )
        }

        // The color it opened on beside the one it would save, then what that color is: the hex
        // to type over, or what following the toggle does, there being no hex to type then
        val header: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Defaults.SpaceMedium)
            ) {
                ColorComparison(
                    before = initialFill,
                    after = activeToggle?.let { followed ->
                        followed.previewGradient.takeIf { it.size > 1 }
                            ?: listOf(followed.previewColor)
                    } ?: listOf(hsv.color)
                )
                AnimatedContent(
                    targetState = activeToggle,
                    transitionSpec = Animations.fadeCrossfade(),
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier.weight(1f),
                    label = "color_picker_value"
                ) { followed ->
                    if (followed == null) {
                        hexField()
                    } else {
                        Text(
                            text = followed.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = dialogSecondaryTextColor()
                        )
                    }
                }
            }
        }

        // A custom color or the one the toggle follows, picked the way the settings pick a mode
        val modes: @Composable () -> Unit = {
            if (toggle != null) {
                val customLabel = stringResource(R.string.custom_color)
                OptionGroup(items = listOf(false, true)) { follows, shape, source, itemModifier ->
                    ModernIconOptionCard(
                        selected = useToggle == follows,
                        onClick = { useToggle = follows },
                        icon = if (follows) toggle.icon else MaterialSymbols.Rounded.Colorize,
                        label = if (follows) toggle.label else customLabel,
                        modifier = itemModifier,
                        compact = true,
                        shape = shape,
                        interactionSource = source
                    )
                }
            }
        }

        // Landscape leaves barely half the height this stacks into, and the panel cannot give way
        // to a scroll because it needs the drag itself. Side by side it fits, and everything that
        // does scroll ends up in the other column, where a swipe still reaches the dialog
        if (landscape) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Defaults.SpaceLarge)
            ) {
                FoldingVisibility(
                    visible = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    panel()
                }
                Column(modifier = Modifier.weight(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(Defaults.SpaceLarge)) {
                        header()
                        modes()
                    }
                    FoldingVisibility(visible = enabled) {
                        Column(
                            modifier = Modifier.padding(top = Defaults.SpaceLarge),
                            verticalArrangement = Arrangement.spacedBy(Defaults.SpaceLarge)
                        ) {
                            swatches()
                            strip()
                        }
                    }
                }
            }
            return@AppDialog
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Defaults.SpaceLarge)) {
                header()
                modes()
            }

            // The manual controls have nothing to offer while the color follows the toggle, and
            // leaving them grayed out in place would only hold the room they need. Spaced from
            // within, so the gap above them folds away along with them
            FoldingVisibility(visible = enabled) {
                Column(
                    modifier = Modifier.padding(top = Defaults.SpaceLarge),
                    verticalArrangement = Arrangement.spacedBy(Defaults.SpaceLarge)
                ) {
                    swatches()
                    panel()
                    strip()
                }
            }
        }
    }
}

/** Presets to a row in the picker, where two short rows leave the panel more room than three. */
private const val PICKER_PRESET_COLUMNS = 8

private val ComparisonSize = 56.dp

/**
 * The color the dialog opened on and the one it would save, the two halves of one circle, so a
 * change reads against where it started. A half is a gradient when its color is one that varies.
 */
@Composable
private fun ColorComparison(before: List<Color>, after: List<Color>) {
    Row(
        modifier = Modifier
            .size(ComparisonSize)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape)
    ) {
        ComparisonHalf(before)
        ComparisonHalf(after)
    }
}

@Composable
private fun RowScope.ComparisonHalf(colors: List<Color>) {
    val solid by animateColorAsState(colors.first(), label = "color_comparison")

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .then(
                if (colors.size > 1) {
                    Modifier.background(Brush.verticalGradient(colors))
                } else {
                    Modifier.background(solid)
                }
            )
    )
}
