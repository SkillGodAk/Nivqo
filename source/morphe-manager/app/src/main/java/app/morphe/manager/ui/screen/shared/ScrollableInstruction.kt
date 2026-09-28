/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.morphe.manager.patcher.patch.withoutSourceIndent

/** Height the text fades out over once there is more of it below the box. */
private val FadeHeight = 24.dp

/**
 * Scrollable instructions box with fade at bottom.
 */
@Composable
fun ScrollableInstruction(
    description: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 300.dp
) {
    val scrollState = rememberScrollState()

    // Localized instructions reach this box straight from strings.xml, unlike the bundle text
    // PatchInfo unindents on the way in
    val instructions = remember(description) { description.withoutSourceIndent() }

    // Fades the text itself rather than laying a strip of one color over it: the box is dropped
    // on surfaces of its own tint, which no single gradient color can be right for
    val fadeHeight = with(LocalDensity.current) { FadeHeight.toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()

                if (scrollState.value < scrollState.maxValue) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startY = size.height - fadeHeight,
                            endY = size.height
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
            }
            .verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsDivider(fullWidth = true)

        Text(
            text = instructions,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.4f
        )
    }
}
