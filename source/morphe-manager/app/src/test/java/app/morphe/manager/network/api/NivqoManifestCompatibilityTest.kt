package app.morphe.manager.network.api

import app.morphe.manager.network.dto.MorpheAsset
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class NivqoManifestCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun currentHushFacebookManifestKeepsLegacyLocalDateTimeFormat() {
        val manifest = """
            {
              "created_at": "2026-10-01T01:47:39",
              "description": "HushFacebook 0.5.0 Nivqo core",
              "changelog_url": "https://raw.githubusercontent.com/SkillGodAk/Nivqo/main/updates/hushfacebook-changelog.md",
              "signature_download_url": "",
              "download_url": "https://raw.githubusercontent.com/SkillGodAk/Nivqo/main/updates/bundles/hushfacebook.mpp",
              "version": "0.5.0-nivqo.2"
            }
        """.trimIndent()

        val asset = json.decodeFromString<MorpheAsset>(manifest)
        assertEquals("0.5.0-nivqo.2", asset.version)
        assertEquals("2026-10-01T01:47:39", asset.createdAt.toString())
        assertEquals(
            "https://raw.githubusercontent.com/SkillGodAk/Nivqo/main/updates/hushfacebook-changelog.md",
            asset.changelogUrl
        )
    }
}
