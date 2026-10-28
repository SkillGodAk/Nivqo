/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * Shows [content] for what it looks like only, such as a preview of a setting: touch and
 * accessibility pass it by, while a drag that starts on it still scrolls the page around it.
 *
 * A layer over the content takes the touch, as only the topmost of overlapping siblings is handed
 * it, and consumes nothing, so the content's clicks never fire and its ancestors see every event.
 */
@Composable
fun DisplayOnly(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier = modifier.clearAndSetSemantics {}) {
        content()
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                }
        )
    }
}
