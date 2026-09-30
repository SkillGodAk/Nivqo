/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.patcher

import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import app.morphe.manager.ui.screen.shared.rememberAccessibilityEnabled
import app.morphe.manager.util.tag
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/** When to stop overestimating progress and always show the actual progress. */
private const val MAX_OVERESTIMATED_PROGRESS = 0.97

/** Progress of a patcher run as the screen shows it, from [rememberDisplayedPatchProgress]. */
@Stable
class DisplayedPatchProgress internal constructor(
    private val targetState: State<Float>,
    private val shownState: State<Float>
) {
    /** Where the shown progress is heading, ahead of the easing toward it. */
    val target: Float get() = targetState.value

    /** The progress to draw. */
    val value: Float get() = shownState.value
}

/**
 * Progress of a patcher run as the screen shows it. The patcher reports progress in coarse steps,
 * some of which run for minutes, so the shown value creeps ahead of the actual one while a step
 * runs and eases toward each new value rather than jumping to it.
 *
 * @param progress Reads the run's actual progress, polled four times a second.
 * @param succeeded Outcome of the run, null while it is still going. Success fills the progress.
 * @param run Identifies the run, so a queue that moves on to the next app starts over from its progress.
 */
@Composable
fun rememberDisplayedPatchProgress(
    progress: () -> Float,
    succeeded: Boolean?,
    run: Any? = null
): DisplayedPatchProgress {
    val target = rememberSaveable(run) { mutableFloatStateOf(progress()) }
    var displayProgress by target
    val currentProgress by rememberUpdatedState(progress)

    LaunchedEffect(run, succeeded) {
        var lastProgressUpdate = 0.0f
        var currentStepStartTime = System.currentTimeMillis()

        while (succeeded == null) {
            val now = System.currentTimeMillis()

            val actualProgress = currentProgress()
            if (lastProgressUpdate != actualProgress) {
                lastProgressUpdate = actualProgress // Progress updated
                currentStepStartTime = now
                if (Log.isLoggable(tag, Log.DEBUG)) {
                    Log.d(tag, "Real progress update: ${(actualProgress * 1000).toInt() / 10.0f}%")
                }
            }

            if (actualProgress >= MAX_OVERESTIMATED_PROGRESS) {
                displayProgress = actualProgress
            } else {
                // Overestimate the progress by about 1% per second, but decays to
                // adding smaller adjustments each second until the current step completes
                fun overEstimateProgressAdjustment(secondsElapsed: Double): Double {
                    // Sigmoid curve. Give larger correct soon after the step starts but then flattens off
                    val maximumValue = 25.0 // Up to 25% over correct
                    val timeConstant = 50.0 // Larger value = longer time until plateau
                    return maximumValue * (1 - exp(-secondsElapsed / timeConstant))
                }

                val secondsSinceStepStarted = (now - currentStepStartTime) / 1000.0
                val overEstimatedProgress = min(
                    MAX_OVERESTIMATED_PROGRESS,
                    actualProgress + 0.01 * overEstimateProgressAdjustment(secondsSinceStepStarted)
                ).toFloat()

                // Don't allow rolling back the progress if it went over,
                // and don't go over 98% unless the actual progress is that far
                displayProgress = max(displayProgress, overEstimatedProgress)
            }

            // Update four times a second
            delay(250.milliseconds)
        }

        // Patching completed - ensure progress reaches 100%
        if (succeeded) {
            displayProgress = 1.0f
        }
    }

    // Skip the 1.5s tween on every progress tick when TalkBack is active so the main thread
    // isn't constantly busy interpolating and can serve accessibility events instead
    val reduceMotion = rememberAccessibilityEnabled()
    val shown = animateFloatAsState(
        targetValue = displayProgress,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 1500, easing = FastOutSlowInEasing),
        label = "progress_animation"
    )
    return remember(target, shown) { DisplayedPatchProgress(target, shown) }
}

/**
 * Ramps the patcher background up with [progress] while [active], and settles it back at normal
 * speed once the run ends or the screen goes.
 *
 * @param progress Reads the progress the speed follows, polled four times a second.
 * @param run Identifies the run, so a queue that moves on to the next app ramps up from rest again.
 */
@Composable
fun PatchingBackgroundSpeedEffect(
    active: Boolean,
    progress: () -> Float,
    onSpeedChange: (Float) -> Unit,
    run: Any? = null
) {
    val currentProgress by rememberUpdatedState(progress)
    val currentOnSpeedChange by rememberUpdatedState(onSpeedChange)

    // Drive background speed: ramps 1x→3x during patching, resets on completion/failure.
    // Uses a coroutine loop so speed tracks displayProgress in real time without recomposition churn
    LaunchedEffect(active, run) {
        if (!active) {
            currentOnSpeedChange(1f)
            return@LaunchedEffect
        }
        // Exponential moving average to smooths sudden progress jumps
        var movingAverage = 0.0f
        // Lower factor has more abrupt animation changes
        val smoothingFactor = 0.25f
        // Patching in progress - poll displayProgress every 250ms (same cadence as progress loop)
        while (true) {
            movingAverage = (1 - smoothingFactor) * movingAverage +
                    smoothingFactor * currentProgress()
            currentOnSpeedChange(1 + movingAverage)
            delay(250.milliseconds)
        }
    }

    // Restore speed when leaving the screen
    DisposableEffect(Unit) {
        onDispose { currentOnSpeedChange(1f) }
    }
}
