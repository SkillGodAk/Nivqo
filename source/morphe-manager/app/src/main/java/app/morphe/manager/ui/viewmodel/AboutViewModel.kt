/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.viewmodel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Translate
import androidx.lifecycle.ViewModel
import app.morphe.manager.util.MORPHE_WEBSITE_URL
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.brands.Github
import compose.icons.fontawesomeicons.brands.RedditAlien

data class SocialLink(
    val name: String,
    val url: String,
    val preferred: Boolean = false,
)

class AboutViewModel : ViewModel() {
    companion object {
        val socials: List<SocialLink> = listOf(
            SocialLink(
                name = "Website",
                url = MORPHE_WEBSITE_URL,
                preferred = true
            ),
            SocialLink(
                name = "Changelog",
                url = "$MORPHE_WEBSITE_URL/changelog"
            ),
            SocialLink(
                name = "GitHub",
                url = "https://github.com/MorpheApp"
            ),
            SocialLink(
                name = "Reddit",
                url = "https://reddit.com/r/MorpheApp"
            ),
            SocialLink(
                name = "Crowdin",
                url = "$MORPHE_WEBSITE_URL/translate"
            )
        )

        private val socialIcons = mapOf(
            "Website" to Icons.Outlined.Public,
            "GitHub" to FontAwesomeIcons.Brands.Github,
            "Changelog" to Icons.AutoMirrored.Outlined.Article,
            "Reddit" to FontAwesomeIcons.Brands.RedditAlien,
            "Crowdin" to Icons.Outlined.Translate,
        )

        fun getSocialIcon(name: String) = socialIcons[name] ?: Icons.Outlined.Language
    }
}
