/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** Pace a line scrolls at, slow enough to read along. */
private val MarqueeVelocity = 30.dp

/** Pause at either end of a line, long enough to take in what has just come into view. */
private val MarqueePause = 1200.milliseconds

/**
 * Scrolls a line too long for its place to its end and back, [iterations] times, while [enabled],
 * fading whichever edge hides something. A line that fits stays put, and one not [enabled] is
 * left to its own overflow, such as an ellipsis.
 *
 * Twice by default, enough to read it whole, after which it settles at its start rather than
 * keep moving beside whatever else is on screen.
 *
 * Scrolled rather than drawn shifted, so the fade knows how far each edge hides and the start of
 * a line at rest is never faded.
 */
@Composable
fun Modifier.marquee(enabled: Boolean = true, iterations: Int = 2): Modifier {
    val scrollState = rememberScrollState()
    val pxPerSecond = with(LocalDensity.current) { MarqueeVelocity.toPx() }

    LaunchedEffect(enabled, scrollState.maxValue) {
        if (!enabled) {
            scrollState.scrollTo(0)
            return@LaunchedEffect
        }
        val distance = scrollState.maxValue
        if (distance <= 0) return@LaunchedEffect

        val spec = tween<Float>((distance / pxPerSecond * 1000).toInt(), easing = LinearEasing)
        repeat(iterations) {
            delay(MarqueePause)
            scrollState.animateScrollTo(distance, spec)
            delay(MarqueePause)
            scrollState.animateScrollTo(0, spec)
        }
    }

    if (!enabled) return this
    return horizontalScrollFade(scrollState).horizontalScroll(scrollState, enabled = false)
}
