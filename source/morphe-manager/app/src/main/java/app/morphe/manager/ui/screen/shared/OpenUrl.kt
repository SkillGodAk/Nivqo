/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import app.morphe.manager.R
import org.koin.compose.koinInject

/**
 * Opens a link for click handlers. A device with nothing to open it in, such as a TV without a
 * browser, gets a message instead of the crash [LocalUriHandler] throws there.
 */
@Composable
fun rememberOpenUrl(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    val messages = koinInject<UserMessages>()
    val failed = stringResource(R.string.sources_management_failed_to_open_url)
    return remember(uriHandler, messages, failed) {
        { url ->
            try {
                uriHandler.openUri(url)
            } catch (_: Exception) {
                messages.show(failed)
            }
        }
    }
}
