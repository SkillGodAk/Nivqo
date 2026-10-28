/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Pull down on [content] to [onRefresh], with a tick on the release that sets it off. Where
 * whatever reloads shows its own placeholders, [isRefreshing] can stay false and the indicator
 * then springs back as soon as it is let go.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * How far down the indicator comes out from, for content drawn under a header. Read where the
     * indicator is placed, so a header that eases its height moves it without recomposing.
     */
    indicatorTop: () -> Dp = { 0.dp },
    content: @Composable BoxScope.() -> Unit
) {
    val view = LocalView.current
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onRefresh()
        },
        modifier = modifier,
        state = state,
        enabled = enabled,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, indicatorTop().roundToPx()) }
            )
        },
        content = content
    )
}
