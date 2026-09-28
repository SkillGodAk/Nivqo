/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView

/** How far a control sinks while held. Shared so every button gives the same push-back. */
const val PressedScale = 0.97f

/**
 * Shared spring-based press-scale factor.
 */
@Composable
fun rememberPressScale(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = PressedScale,
    label: String = "press_scale"
): State<Float> {
    val isPressed by interactionSource.collectIsPressedAsState()

    return animateFloatAsState(
        targetValue = if (enabled && isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = label
    )
}

/**
 * Sinks the node while [interactionSource] reports a press. The animated value is read inside
 * the layer, so holding a button repaints it instead of recomposing it on every frame.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = PressedScale,
    label: String = "press_scale"
): Modifier {
    val scale = rememberPressScale(interactionSource, enabled, pressedScale, label)

    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Wraps [onClick] so it fires a [HapticFeedbackConstants.VIRTUAL_KEY] pulse first. The
 * returned lambda is remembered against [onClick] so repeated recompositions keep the
 * same reference.
 */
@Composable
fun rememberHapticClick(onClick: () -> Unit): () -> Unit {
    val view = LocalView.current
    return remember(view, onClick) {
        {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
    }
}
