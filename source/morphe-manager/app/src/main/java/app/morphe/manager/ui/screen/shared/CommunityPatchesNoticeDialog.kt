/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import app.morphe.manager.R
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.util.COMMUNITY_PATCHES_URL
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Opens the community patches website, or calls [onNoticeNeeded] for the caller to show
 * [CommunityPatchesNoticeDialog] while the user has not yet agreed to it.
 */
@Composable
fun rememberOpenCommunityPatches(onNoticeNeeded: () -> Unit): () -> Unit {
    val prefs: PreferencesManager = koinInject()
    val acknowledged by prefs.communityNoticeAcknowledged.getAsState()
    val openUrl = rememberOpenUrl()
    val currentOnNoticeNeeded by rememberUpdatedState(onNoticeNeeded)
    return { if (acknowledged) openUrl(COMMUNITY_PATCHES_URL) else currentOnNoticeNeeded() }
}

/**
 * Opens the community patches website, saying first that nothing it lists is reviewed by Morphe.
 * Agreeing to it once is enough, see [rememberOpenCommunityPatches].
 */
@Composable
fun DialogHostScope.CommunityPatchesNoticeDialog(onDismiss: () -> Unit) {
    val prefs: PreferencesManager = koinInject()
    val scope = rememberCoroutineScope()
    val openUrl = rememberOpenUrl()
    ConfirmDialog(
        title = stringResource(R.string.sources_dialog_community_notice_title),
        message = stringResource(R.string.sources_dialog_community_notice_message),
        primaryText = stringResource(R.string.open),
        isPrimaryDestructive = false,
        onDismiss = onDismiss,
        onConfirm = {
            // Written in full even though the dialog leaves at once
            scope.launch { withContext(NonCancellable) { prefs.communityNoticeAcknowledged.update(true) } }
            onDismiss()
            openUrl(COMMUNITY_PATCHES_URL)
        }
    )
}
