/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 *
 * Original hard forked code:
 * https://github.com/Jman-Github/Universal-ReVanced-Manager/blob/597b3173a004f5a9aae54326046dd7fd4c5b7777/app/src/main/java/app/revanced/manager/ui/component/AppLabel.kt
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.manager.ui.screen.shared

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.util.AppDataResolver
import app.morphe.manager.util.AppDataSource
import app.morphe.manager.util.cleanPackageLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Universal app label component.
 *
 * Automatically resolves label from available sources:
 * installed app → original APK → patched APK → constants → package name fallback.
 */
@Composable
fun AppLabel(
    modifier: Modifier = Modifier,
    packageInfo: PackageInfo? = null,
    packageName: String? = null,
    style: TextStyle = LocalTextStyle.current,
    defaultText: String? = stringResource(R.string.not_installed),
    preferredSource: AppDataSource = AppDataSource.INSTALLED,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    // If PackageInfo is provided, use the simple implementation
    if (packageInfo != null) {
        SimpleAppLabel(
            packageInfo = packageInfo,
            modifier = modifier,
            style = style,
            defaultText = defaultText,
            maxLines = maxLines,
            overflow = overflow
        )
        return
    }

    // If only package name is provided, resolve from multiple sources
    if (packageName != null) {
        ResolvedAppLabel(
            packageName = packageName,
            modifier = modifier,
            style = style,
            defaultText = defaultText,
            preferredSource = preferredSource,
            maxLines = maxLines,
            overflow = overflow
        )
        return
    }

    // Fallback: show default text if neither is provided
    Text(
        text = defaultText ?: stringResource(R.string.not_installed),
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        overflow = overflow
    )
}

/**
 * Simple label display when PackageInfo is already available.
 */
@Composable
private fun SimpleAppLabel(
    packageInfo: PackageInfo,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    defaultText: String? = null,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    val context = LocalContext.current

    // Attempt a cheap synchronous resolution first so we never show a shimmer
    // when the label is already available in memory
    val initialLabel = remember(packageInfo.packageName) {
        runCatching { packageInfo.readableLabel(context.packageManager) }.getOrNull()
    }

    var label: String? by rememberSaveable(packageInfo.packageName) {
        mutableStateOf(initialLabel)
    }

    // Only dispatch to IO when the synchronous attempt didn't produce a result
    if (label == null) {
        LaunchedEffect(packageInfo.packageName) {
            label = withContext(Dispatchers.IO) {
                runCatching { packageInfo.readableLabel(context.packageManager) }.getOrNull() ?: defaultText
            }
        }
    }

    Text(
        label ?: defaultText ?: stringResource(R.string.loading),
        modifier = Modifier
            .then(
                // A pill stands in for the text until the real label arrives
                if (label == null) {
                    Modifier
                        .background(MaterialTheme.colorScheme.inverseOnSurface, Defaults.PillShape)
                        .drawWithContent {}
                } else {
                    Modifier
                }
            )
            .then(modifier),
        style = style,
        maxLines = maxLines,
        overflow = overflow
    )
}

/**
 * Resolved label from any available source when only package name is known.
 */
@Composable
private fun ResolvedAppLabel(
    packageName: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    defaultText: String? = null,
    preferredSource: AppDataSource = AppDataSource.INSTALLED,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    val appDataResolver: AppDataResolver = koinInject()

    var label by remember(packageName) { mutableStateOf<String?>(null) }
    var isLoading by remember(packageName) { mutableStateOf(true) }

    LaunchedEffect(packageName, preferredSource) {
        // Use resolveAppData to get complete data in one call
        val resolvedData = appDataResolver.resolveAppData(packageName, preferredSource)
        // If resolved name is same as package name, and we have a default, use default
        label = if (resolvedData.displayName == packageName && defaultText != null) {
            defaultText
        } else {
            resolvedData.displayName
        }
        isLoading = false
    }

    if (isLoading) {
        // Show shimmer while loading
        ShimmerText(
            modifier = modifier,
            widthFraction = 0.6f,
            height = with(LocalResources.current.displayMetrics) {
                (style.fontSize.value * density).dp
            }
        )
    } else {
        Text(
            label ?: stringResource(R.string.loading),
            modifier = modifier,
            style = style,
            maxLines = maxLines,
            overflow = overflow
        )
    }
}

/**
 * The label worth showing for this package, cleaned as [app.morphe.manager.util.PM] cleans one, or its
 * non-localized label when the launcher one is no more than the package name.
 */
internal fun PackageInfo.readableLabel(packageManager: PackageManager): String? =
    applicationInfo?.loadLabel(packageManager)?.toString()
        ?.let { cleanPackageLabel(it, packageName) }
        ?.takeIf { it.isNotBlank() && it != packageName }
        ?: applicationInfo?.nonLocalizedLabel?.toString()?.takeIf { it.isNotBlank() }
