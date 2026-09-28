/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.patcher

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morphe.manager.R
import app.morphe.manager.ui.model.PostPatchPrompts
import app.morphe.manager.ui.screen.settings.system.NotificationPermissionDialog
import app.morphe.manager.ui.screen.shared.AppDialog
import app.morphe.manager.ui.screen.shared.AppDialogButtonRow
import app.morphe.manager.ui.screen.shared.LocalDialogSecondaryTextColor

/**
 * Notification permission and onboarding tour dialogs raised by [PostPatchPrompts].
 *
 * @param onStartTour starts the tour, which runs on the home screen.
 * @param onDeclineTour marks the tour as declined so it is not offered again.
 * @param onLeave leaves the finished screen after either tour button, so the tour or the
 *        home screen comes next rather than the result the user already saw.
 */
@Composable
fun PostPatchPromptDialogs(
    prompts: PostPatchPrompts,
    onStartTour: () -> Unit,
    onDeclineTour: () -> Unit,
    onLeave: () -> Unit
) {
    val showNotification by prompts.notification.collectAsStateWithLifecycle()
    val showTour by prompts.tour.collectAsStateWithLifecycle()

    if (showNotification) {
        NotificationPermissionDialog(
            title = stringResource(R.string.notification_post_patch_dialog_title),
            onDismissRequest = { prompts.onNotificationResult(granted = false) },
            onPermissionResult = prompts::onNotificationResult
        )
    }

    if (showTour) {
        AppDialog(
            onDismissRequest = {
                prompts.consumeTour()
                onDeclineTour()
            },
            title = stringResource(R.string.tour_prompt_title),
            footer = {
                AppDialogButtonRow(
                    primaryText = stringResource(R.string.tour_prompt_confirm),
                    onPrimaryClick = {
                        prompts.consumeTour()
                        onStartTour()
                        onLeave()
                    },
                    secondaryText = stringResource(R.string.skip),
                    onSecondaryClick = {
                        prompts.consumeTour()
                        onDeclineTour()
                        onLeave()
                    }
                )
            }
        ) {
            Text(
                text = stringResource(R.string.tour_prompt_desc),
                style = MaterialTheme.typography.bodyLarge,
                color = LocalDialogSecondaryTextColor.current,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
