/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morphe.manager.R
import app.morphe.manager.util.ChangelogEntry
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.State as MarkdownRenderState

/**
 * Footer action opening the GitHub release page for [pageUrl].
 * Null when there is no page to open, so callers can drop it from the group.
 */
@Composable
fun changelogAction(pageUrl: String?): DialogAction? {
    val uriHandler = LocalUriHandler.current
    val text = stringResource(R.string.changelog)

    return pageUrl?.let { url ->
        DialogAction(
            text = text,
            onClick = { uriHandler.openUri(url) },
            icon = Icons.AutoMirrored.Outlined.Article,
            emphasis = DialogActionEmphasis.Outlined
        )
    }
}

/**
 * Loading state with shimmer effect for the entire changelog section
 */
@Composable
fun ChangelogSectionLoading(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Defaults.ContentPadding)
    ) {
        // Header shimmer
        ShimmerChangelogHeader()

        // Changelog content shimmer
        ShimmerChangelog()
    }
}

/**
 * Displays a single [ChangelogEntry] parsed from CHANGELOG.md.
 */
@Composable
fun ChangelogEntrySection(
    entry: ChangelogEntry,
    headerIcon: ImageVector = Icons.Outlined.NewReleases,
    precomputedMarkdown: MarkdownRenderState? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Defaults.ContentPadding)
    ) {
        ChangelogEntryHeader(
            version = entry.version,
            date = entry.date,
            icon = headerIcon
        )
        if (entry.content.isNotBlank()) {
            Changelog(markdown = entry.content, precomputedState = precomputedMarkdown)
        }
    }
}

/**
 * Version/date header card for a single changelog entry.
 */
@Composable
private fun ChangelogEntryHeader(
    version: String,
    date: String?,
    icon: ImageVector
) {
    HeroInfoCard(
        icon = icon,
        title = if (version.startsWith("v")) version else "v$version",
        titleColor = LocalDialogTextColor.current,
        subtitle = if (date != null) {
            {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = date,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current,
                    fontWeight = FontWeight.Medium
                )
            }
        } else null
    )
}

/**
 * Renders sanitized changelog Markdown.
 */
@Composable
fun Changelog(
    markdown: String,
    precomputedState: MarkdownRenderState? = null
) {
    val colors = markdownColor(
        text = MaterialTheme.colorScheme.onSurface,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
        dividerColor = MaterialTheme.colorScheme.outlineVariant
    )
    val typography = markdownTypography(
        h1 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        h2 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        h3 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        text = MaterialTheme.typography.bodyMedium,
        list = MaterialTheme.typography.bodyMedium,
        code = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.sp
        )
    )
    if (precomputedState != null) {
        Markdown(state = precomputedState, colors = colors, typography = typography)
    } else {
        Markdown(
            content = markdown.trimIndent(),
            retainState = true,
            loading = { ShimmerChangelog(modifier = it) },
            colors = colors,
            typography = typography
        )
    }
}
