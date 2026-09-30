/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.viewmodel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.NewReleases
import androidx.lifecycle.ViewModel
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.brands.Github

data class SocialLink(
    val name: String,
    val url: String,
    val preferred: Boolean = false,
)

class AboutViewModel : ViewModel() {
    companion object {
        private const val NIVQO_REPOSITORY = "https://github.com/SkillGodAk/Nivqo"

        val socials: List<SocialLink> = listOf(
            SocialLink(
                name = "GitHub",
                url = NIVQO_REPOSITORY,
                preferred = true
            ),
            SocialLink(
                name = "Releases",
                url = "$NIVQO_REPOSITORY/releases"
            ),
            SocialLink(
                name = "Changelog",
                url = "$NIVQO_REPOSITORY/blob/main/CHANGELOG.md"
            ),
            SocialLink(
                name = "Issues",
                url = "$NIVQO_REPOSITORY/issues"
            )
        )

        private val socialIcons = mapOf(
            "GitHub" to FontAwesomeIcons.Brands.Github,
            "Releases" to Icons.Outlined.NewReleases,
            "Changelog" to Icons.AutoMirrored.Outlined.Article,
            "Issues" to Icons.Outlined.BugReport,
        )

        fun getSocialIcon(name: String) = socialIcons[name] ?: Icons.Outlined.Language
    }
}
