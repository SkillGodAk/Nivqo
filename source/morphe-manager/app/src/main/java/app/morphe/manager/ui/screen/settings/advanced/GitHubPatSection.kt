/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.advanced

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morphe.manager.R
import app.morphe.manager.network.api.GitHubPatCheck
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.SettingsViewModel
import com.composables.icons.fontawesome.FontAwesome
import com.composables.icons.fontawesome.brands.Github
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*
import kotlinx.coroutines.launch

/**
 * Kept in English because GitHub has no localized UI and users look for this exact name there.
 */
private const val GITHUB_PAT_NAME = "GitHub Personal Access Token"

/** All a token needs for Morphe: pull request builds and the repositories it can read. */
private const val GITHUB_PAT_SCOPE = "public_repo"

private const val CREATE_GITHUB_PAT_URL =
    "https://github.com/settings/tokens/new?scopes=$GITHUB_PAT_SCOPE&description=morphe-manager-github-integration"

/**
 * GitHub PAT settings item, a row of the updates group. It says whether GitHub turned the token
 * down, since requests then go out without it and nothing else would show it.
 */
@Composable
fun GitHubPatSettingsItem(settingsViewModel: SettingsViewModel) {
    val prefs = settingsViewModel.prefs
    val pat by prefs.gitHubPat.getAsState()
    val includeInExport by prefs.includeGitHubPatInExports.getAsState()
    val rejected by settingsViewModel.gitHubPatRejected.collectAsStateWithLifecycle(false)
    val showDialog = rememberSaveable { mutableStateOf(false) }
    val hasPat = pat.isNotBlank()
    val tone = when {
        rejected -> SemanticTone.Warning
        hasPat -> SemanticTone.Primary
        else -> SemanticTone.Neutral
    }

    SettingsItem(
        onClick = { showDialog.value = true },
        icon = MaterialSymbols.Rounded.Vpn_key,
        title = stringResource(R.string.settings_advanced_github_pat),
        subtitle = stringResource(
            when {
                rejected -> R.string.settings_advanced_github_pat_rejected
                hasPat -> R.string.settings_advanced_github_pat_configured
                else -> R.string.settings_advanced_github_pat_summary
            }
        ),
        statusContent = {
            StatusCircleIcon(
                icon = if (rejected) MaterialSymbols.Rounded.Warning else MaterialSymbols.Rounded.Check,
                containerColor = tone.container,
                contentColor = tone.content
            )
        }
    )

    DialogHost(visible = showDialog.value) {
        GitHubPatDialog(
            currentPat = pat,
            currentIncludeInExport = includeInExport,
            onCheck = settingsViewModel::checkGitHubPat,
            onSave = { newPat, include ->
                settingsViewModel.setGitHubPat(newPat, include)
                showDialog.value = false
            },
            onDismiss = { showDialog.value = false }
        )
    }
}

/**
 * Sets the token, checking it with GitHub first: one GitHub turns down is not saved, while one it
 * could not be asked about is, as it may well be fine. The saved token is checked as the dialog
 * opens, so it says whose it is before anything is changed.
 */
@Composable
private fun DialogHostScope.GitHubPatDialog(
    currentPat: String,
    currentIncludeInExport: Boolean,
    onCheck: suspend (String) -> GitHubPatCheck,
    onSave: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val pat = rememberSaveable { mutableStateOf(currentPat) }
    val includeInExport = rememberSaveable { mutableStateOf(currentIncludeInExport) }
    // Tagged with the token it was made for, so editing the field leaves it behind
    val lastCheck = remember { mutableStateOf<Pair<String, GitHubPatCheck>?>(null) }
    val checking = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val openUrl = rememberOpenUrl()

    val token = pat.value.trim()
    val check = lastCheck.value?.takeIf { it.first == token }?.second

    suspend fun runCheck(): GitHubPatCheck {
        checking.value = true
        return try {
            onCheck(token).also { lastCheck.value = token to it }
        } finally {
            checking.value = false
        }
    }

    LaunchedEffect(Unit) {
        if (token.isNotEmpty()) runCheck()
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title = GITHUB_PAT_NAME,
        description = stringResource(R.string.settings_advanced_github_pat_description),
        footer = {
            AppDialogButtonRow(
                primaryText = stringResource(R.string.save),
                onPrimaryClick = {
                    scope.launch {
                        val verdict = if (token.isEmpty()) null else check ?: runCheck()
                        if (verdict != GitHubPatCheck.Rejected) onSave(token, includeInExport.value)
                    }
                },
                primaryIcon = MaterialSymbols.Rounded.Save,
                primaryEnabled = !checking.value,
                // A saved token is taken away here, there being nothing else to cancel but the dialog
                secondaryText = stringResource(if (currentPat.isNotBlank()) R.string.remove else android.R.string.cancel),
                secondaryIcon = MaterialSymbols.Rounded.Delete.takeIf { currentPat.isNotBlank() },
                onSecondaryClick = {
                    if (currentPat.isNotBlank()) onSave("", includeInExport.value) else onDismiss()
                }
            )
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Defaults.SpaceLarge)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)) {
                AppDialogTextField(
                    value = pat.value,
                    onValueChange = { pat.value = it },
                    label = { Text(stringResource(R.string.settings_advanced_github_pat)) },
                    placeholder = { Text("ghp_xxxxxxxxxxxxxxx") },
                    leadingIcon = { Icon(imageVector = MaterialSymbols.Rounded.Key, contentDescription = null) },
                    isError = check == GitHubPatCheck.Rejected,
                    isPassword = true,
                    showClearButton = true
                )
                GitHubPatCheckFooter(check = check, checking = checking.value)
            }

            SettingsItem(
                onClick = { openUrl(CREATE_GITHUB_PAT_URL) },
                showBorder = true,
                icon = FontAwesome.Brands.Github,
                title = stringResource(R.string.settings_advanced_github_pat_create),
                subtitle = stringResource(R.string.settings_advanced_github_pat_create_description, GITHUB_PAT_SCOPE),
                // Leaves for the browser rather than going on within the dialog
                trailingContent = { ThemedIcon(icon = MaterialSymbols.Rounded.Open_in_new.autoMirrored) }
            )

            Column(verticalArrangement = Arrangement.spacedBy(Defaults.SpaceSmall)) {
                SettingsSwitchItem(
                    checked = includeInExport.value,
                    onToggle = { includeInExport.value = !includeInExport.value },
                    showBorder = true,
                    icon = MaterialSymbols.Rounded.Upload,
                    title = stringResource(R.string.settings_advanced_github_pat_export_include_label),
                    subtitle = stringResource(R.string.settings_advanced_github_pat_export_include_supporting)
                )
                // Said right under the switch rather than asked first, so turning it on stays one tap
                FoldingVisibility(visible = includeInExport.value) {
                    SettingsFooter(
                        text = stringResource(R.string.settings_advanced_github_pat_export_warning),
                        tone = SemanticTone.Warning
                    )
                }
            }
        }
    }
}

/** What GitHub made of the token in the field, while it is asked and once it has answered. */
@Composable
private fun GitHubPatCheckFooter(check: GitHubPatCheck?, checking: Boolean) {
    val footer = when {
        checking -> CheckFooter(
            stringResource(R.string.settings_advanced_github_pat_checking),
            MaterialSymbols.Rounded.Hourglass_empty,
            SemanticTone.Neutral
        )
        check is GitHubPatCheck.Valid -> CheckFooter(
            stringResource(R.string.settings_advanced_github_pat_account, check.login),
            MaterialSymbols.Rounded.Check_circle,
            SemanticTone.Success
        )
        check == GitHubPatCheck.Rejected -> CheckFooter(
            stringResource(R.string.settings_advanced_github_pat_rejected),
            MaterialSymbols.Rounded.Error,
            SemanticTone.Error
        )
        check == GitHubPatCheck.Unreachable -> CheckFooter(
            stringResource(R.string.settings_advanced_github_pat_unreachable),
            MaterialSymbols.Rounded.Cloud_off,
            SemanticTone.Neutral
        )
        else -> null
    }

    FoldingVisibility(visible = footer != null) {
        AnimatedContent(
            targetState = rememberLatest(footer),
            transitionSpec = Animations.fadeCrossfade(),
            label = "githubPatCheck"
        ) { shown ->
            if (shown != null) {
                SettingsFooter(text = shown.text, tone = shown.tone, icon = shown.icon)
            }
        }
    }
}

private data class CheckFooter(val text: String, val icon: ImageVector, val tone: SemanticTone)
