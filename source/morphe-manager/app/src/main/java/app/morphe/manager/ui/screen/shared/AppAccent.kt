/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morphe.manager.domain.bundles.PatchBundleSource
import app.morphe.manager.domain.bundles.PatchBundleSource.Extensions.avatarUrls
import app.morphe.manager.domain.bundles.PatchBundleSource.Extensions.isDefault
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.patcher.patch.BundleAppMetadata
import app.morphe.manager.ui.theme.ThemeTraitsDefaults
import app.morphe.manager.util.compositeOver
import app.morphe.manager.util.contrastingContent
import app.morphe.manager.util.isDarkBackground
import app.morphe.manager.util.isExtremeAccent
import app.morphe.manager.util.rememberSourceAccent
import org.koin.compose.koinInject

/**
 * How strongly each kind of surface takes an app's or a source's own color. Every tint in the app
 * is one of these, so a card, a panel on it and a pill on that step up in the same order anywhere.
 */
object AccentAlpha {
    /** A header band, which spans the dialog and only has to hint at the color. */
    const val BAND = 0.15f

    /** A group header, over [BAND] since at that alpha a larger area reads as a shade. */
    const val CARD = 0.18f

    /** A control or panel a step over the surface it sits on, such as a badge, so it stands out of it. */
    const val STEP = 0.18f

    /** A control a step over [STEP], for one that leads, such as a pill or an engaged title action. */
    const val LEAD = 0.3f

    /** A card's or an outlined control's edge, one alpha for both so neither pulls the eye. */
    const val BORDER = 0.3f
}

/**
 * Fill of a surface that carries an app's own color, the way the app details and the patch lists
 * head themselves.
 */
@Composable
fun appAccentFill(accentColor: Color?): Color =
    appAccentTint(accentColor, alpha = AccentAlpha.BAND, neutral = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))

/** Fill of a group header in an app's own color, or null without one. Cards take [cardFill]. */
@Composable
fun appAccentCardFill(accentColor: Color?): Color? = usableAppAccent(accentColor)?.copy(alpha = AccentAlpha.CARD)

/** Border of a card that carries an app's own color, see [AccentAlpha.BORDER]. */
@Composable
fun appAccentBorder(accentColor: Color?): Color =
    appAccentTint(accentColor, alpha = AccentAlpha.BORDER, neutral = MaterialTheme.colorScheme.outlineVariant)

/**
 * Hands [accentColor] to everything drawn inside, see [LocalAccent], for a dialog or a header that
 * wears it without a card of its own.
 */
@Composable
fun ProvideAccent(accentColor: Color?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAccent provides usableAppAccent(accentColor), content = content)
}

/**
 * [ProvideAccent] for a card filled with [fill], which also becomes the [LocalCardBackground] the
 * badges on the card stand out of. The fill is the one the card is heading to rather than an
 * animated one, so what is resolved against it settles once instead of chasing every frame.
 */
@Composable
fun ProvideCardAccent(accentColor: Color?, fill: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAccent provides usableAppAccent(accentColor),
        LocalCardBackground provides fill.compositeOver(MaterialTheme.colorScheme.background),
        content = content
    )
}

/**
 * A [StatusBadge] a step over the app-colored surface it sits on, see [LocalAccent]. Without a color,
 * a neutral badge takes [neutralVeil] so it does not outweigh colored ones, other tones keep theirs.
 */
@Composable
fun AppAccentBadge(
    text: String,
    modifier: Modifier = Modifier,
    accentColor: Color? = LocalAccent.current,
    icon: ImageVector? = null,
    tone: SemanticTone = SemanticTone.Neutral,
    onClick: (() -> Unit)? = null
) {
    val veiled = accentColor != null || tone == SemanticTone.Neutral
    StatusBadge(
        text = text,
        modifier = modifier,
        icon = icon,
        tone = tone,
        containerColor = if (veiled) {
            appAccentTint(accentColor, alpha = AccentAlpha.STEP, neutral = neutralVeil())
        } else {
            tone.container
        },
        contentColor = if (veiled) MaterialTheme.colorScheme.onBackground else tone.content,
        onClick = onClick
    )
}

/**
 * Veil of [ink], the text color of the surface below, for anything that must stand on any surface
 * without tinting it: neutral badges, destructive actions, plain tiles. One veil, so no stray grays.
 */
@Composable
fun neutralVeil(ink: Color = MaterialTheme.colorScheme.onBackground): Color =
    ink.copy(alpha = if (ink.isDarkBackground()) 0.08f else 0.1f)

/**
 * Content color over [fill], a tint of an app's color: the header's text over a see-through tint,
 * as its badges have, and whichever of black and white stands out of the solid color.
 */
@Composable
fun appAccentContent(fill: Color): Color =
    if (fill.alpha < 1f) MaterialTheme.colorScheme.onBackground else fill.contrastingContent()

/**
 * Line in the surrounding color, see [LocalAccent], hanging content from what it belongs to, such as
 * download steps or a patch's options. It fills the height given, so its row is measured to content.
 */
@Composable
fun AccentRail(modifier: Modifier = Modifier) {
    val accent = LocalAccent.current ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .width(AccentRailWidth)
            .background(accent.copy(alpha = AccentAlpha.BORDER), CircleShape)
    )
}

/** Width of an [AccentRail]. */
val AccentRailWidth = 2.dp

/** What the sources declare about each app they patch, by package name. */
@Composable
fun rememberAppMetadata(): Map<String, BundleAppMetadata> {
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val metadata by patchBundleRepository.allAppMetadata.collectAsStateWithLifecycle()
    return metadata
}

/** Color the sources declare [packageName] with, or null where none of them does or there is no app. */
@Composable
fun rememberAppColor(packageName: String?): Color? {
    val metadata = rememberAppMetadata()
    return packageName?.let { metadata[it]?.downloadColor }
}

/**
 * [accentColor] as it can be shown, or null where there is none to show. Monochrome and the color
 * accents setting swap the color for the theme's accent, and what is left must pass [readableAccent].
 */
@Composable
fun usableAppAccent(accentColor: Color?): Color? =
    readableAccent(accentColor?.let { ThemeTraitsDefaults.accentColor(it) })

/**
 * [accentColor], or null where it is near-black or near-white and reads as a stain rather than a
 * color. Kept as given otherwise, for a color picked for what it says, such as an error red, which
 * [usableAppAccent] would trade for the monochrome theme's accent.
 */
fun readableAccent(accentColor: Color?): Color? = accentColor?.takeUnless { it.isExtremeAccent() }

/** [accentColor] at [alpha], or [neutral] where [usableAppAccent] finds none. */
@Composable
private fun appAccentTint(accentColor: Color?, alpha: Float, neutral: Color): Color =
    usableAppAccent(accentColor)?.copy(alpha = alpha) ?: neutral

/** Every source by uid, for what draws a source's icon or name from its uid alone. */
@Composable
fun rememberSourcesByUid(): Map<Int, PatchBundleSource> {
    val patchBundleRepository: PatchBundleRepository = koinInject()
    val sources by patchBundleRepository.sources.collectAsStateWithLifecycle()
    return remember(sources) { sources.associateBy { it.uid } }
}

/** The color [bundle]'s icon reads as, see [rememberSourceAccent]. */
@Composable
fun rememberBundleAccent(bundle: PatchBundleSource): Color? {
    val avatarUrls = bundle.avatarUrls
    return rememberSourceAccent(bundle.isDefault, avatarUrls.primary, avatarUrls.fallback)
}
