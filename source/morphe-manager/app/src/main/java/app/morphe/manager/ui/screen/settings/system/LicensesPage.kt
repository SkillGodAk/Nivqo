/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.system

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import app.morphe.manager.R
import app.morphe.manager.ui.screen.shared.*
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.LibraryColors
import com.mikepenz.aboutlibraries.ui.compose.LibraryDefaults
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.m3.chipColors
import com.mikepenz.aboutlibraries.ui.compose.m3.libraryColors
import com.mikepenz.aboutlibraries.ui.compose.util.strippedLicenseContent
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryActionKind

private const val NOTICE_UNIQUE_ID = "app.morphe.manager"
private val urlRegex = Regex("(https?://[\\w./?=&%-]+)")
// Copied verbatim from NOTICE in project root. Update manually if NOTICE changes.
private const val NOTICE_TEXT = """
Morphe NOTICE

https://github.com/MorpheApp/morphe-manager

=============

Portions of this software are provided "AS IS" by the Morphe software project.
Any express or implied warranties, including the implied warranties of
merchantability and fitness for a particular purpose, are disclaimed.


GPLv3 Section 7c: Prohibiting Misrepresentation of Origin

You are prohibited from misrepresenting the origin of the Program,
and modified versions of the Program must be identified and marked in
reasonable ways as different from the original version so as not to cause
confusion regarding their origin.


GPLv3 Section 7e: Declining Grant of Trademark Rights

This License does not grant any rights or permission under trademark law
to use the name "Morphe" or any of the Program's trade names, trademarks,
service marks, or logos.
"""

/**
 * The open-source libraries the manager is built on, with their licenses, on a page of their own.
 * A library's license opens over the list.
 *
 * @param onBack Leaves the page for the one it was opened from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesPage(onBack: () -> Unit) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    val count = libraries?.libraries?.size
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val lazyListState = rememberLazyListState()
    var openDialog by remember { mutableStateOf<Library?>(null) }
    var openSheet by remember { mutableStateOf<Library?>(null) }

    val chipColors = LibraryDefaults.chipColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    )
    val colors = LibraryDefaults.libraryColors(
        libraryBackgroundColor = MaterialTheme.colorScheme.background,
        libraryContentColor = MaterialTheme.colorScheme.onSurface,
        versionChipColors = chipColors,
        licenseChipColors = chipColors,
        fundingChipColors = chipColors,
    )

    PinnedHeaderPage(
        header = {
            PageHeader(
                title = stringResource(R.string.opensource_licenses),
                subtitle = count?.let { pluralStringResource(R.plurals.library_count, it, it.toString()) },
                onBack = onBack
            )
        },
        scrollState = lazyListState
    ) { insets ->
        LibrariesContainer(
            modifier = Modifier
                .fillMaxSize()
                .verticalScrollFade(lazyListState),
            libraries = libraries,
            dialogLibrary = openDialog,
            sheetLibrary = openSheet,
            onDialogLibraryChange = { openDialog = it },
            onSheetLibraryChange = { openSheet = it },
            lazyListState = lazyListState,
            contentPadding = PaddingValues(
                start = insets.horizontal,
                top = insets.top + Defaults.SpaceMedium,
                end = insets.horizontal,
                bottom = navigationBar + Defaults.SpaceLarge
            ),
            colors = colors,
            onActionClick = { library, kind ->
                if (kind == LibraryActionKind.License) {
                    openDialog = library
                    true
                } else false
            },
            licenseDialogBody = { library, modifier ->
                if (library.uniqueId == NOTICE_UNIQUE_ID) {
                    AutoLinkText(
                        text = NOTICE_TEXT,
                        modifier = modifier,
                        color = colors.dialogContentColor
                    )
                } else {
                    ClickableLicenseDialogBody(
                        library = library,
                        colors = colors,
                        modifier = modifier
                    )
                }
            }
        )

        ListScrollbar(
            listState = lazyListState,
            modifier = Modifier.padding(
                top = insets.top + Defaults.SpaceMedium,
                bottom = navigationBar + Defaults.SpaceMedium
            )
        )
        ScrollToTopButton(listState = lazyListState, extraBottomPadding = navigationBar)
    }
}

private val Library.linkifiedLicensesBody: String
    get() {
        val header = website?.let { "$it\n\n" }.orEmpty()

        val body = licenses.joinToString(separator = "\n\n\n\n") { license ->
            buildString {
                license.url?.let { append(it).append("\n\n") }
                license.strippedLicenseContent?.let { append(it) }
            }
        }

        return header + body
    }

@Composable
private fun ClickableLicenseDialogBody(
    library: Library,
    colors: LibraryColors,
    modifier: Modifier,
) {
    AutoLinkText(
        text = library.linkifiedLicensesBody,
        modifier = modifier,
        color = colors.dialogContentColor
    )
}

@Composable
private fun AutoLinkText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    val linkColor = MaterialTheme.colorScheme.primary

    val annotated = remember(text, linkColor) { buildAnnotatedString {
        var lastIndex = 0
        val linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor))

        urlRegex.findAll(text).forEach { match ->
            val url = match.value

            // Add text before the URL
            append(text.substring(lastIndex, match.range.first))

            // Add the URL as a link the text opens itself when tapped
            withLink(LinkAnnotation.Url(url, linkStyles)) {
                append(url)
            }

            lastIndex = match.range.last + 1
        }

        // Add remaining text
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    } }
    Text(
        text = annotated,
        modifier = modifier,
        style = LocalTextStyle.current.copy(color = color)
    )
}
