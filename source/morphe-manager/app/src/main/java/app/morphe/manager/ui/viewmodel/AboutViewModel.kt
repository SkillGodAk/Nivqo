/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.viewmodel

import androidx.lifecycle.ViewModel
import app.morphe.manager.ui.screen.shared.autoMirrored
import app.morphe.manager.util.MORPHE_WEBSITE_URL
import com.composables.icons.fontawesome.FontAwesome
import com.composables.icons.fontawesome.brands.*
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*

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
            "Website" to MaterialSymbols.Rounded.Public,
            "GitHub" to FontAwesome.Brands.Github,
            "Changelog" to MaterialSymbols.Rounded.Article.autoMirrored,
            "Reddit" to FontAwesome.Brands.Reddit,
            "Crowdin" to MaterialSymbols.Rounded.Translate,
        )

        fun getSocialIcon(name: String) = socialIcons[name] ?: MaterialSymbols.Rounded.Language
    }
}
