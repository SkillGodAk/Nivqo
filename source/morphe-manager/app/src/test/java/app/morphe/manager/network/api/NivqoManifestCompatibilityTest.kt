package app.morphe.manager.network.api

import app.morphe.manager.network.dto.MorpheAsset
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import app.morphe.manager.util.ChangelogParser

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

    @Test
    fun combinedMorpheSourceUsesLocalDateTimeAndParsesScopedChangelog() {
        val manifest = """
            {
              "created_at": "2026-10-02T15:41:41",
              "description": "Nivqo Combined Test",
              "signature_download_url": "",
              "download_url": "https://raw.githubusercontent.com/SkillGodAk/Nivqo/08757cd6b119765c231ccb3c447cfd2f8f5bc5bc/test-assets/nivqo-combined-0.1.0-dev.1.mpp",
              "version": "0.1.0-dev.1"
            }
        """.trimIndent()

        val asset = json.decodeFromString<MorpheAsset>(manifest)
        assertEquals("0.1.0-dev.1", asset.version)
        assertEquals("2026-10-02T15:41:41", asset.createdAt.toString())

        val entries = ChangelogParser.parse(
            """
            ## [0.1.0-dev.1](https://github.com/SkillGodAk/Nivqo/commit/75e3555) (2026-10-02)

            ### New Features

            * **Facebook:** Combined HushFacebook source.
            * **Messenger:** Combined HushMessenger source.
            """.trimIndent()
        )
        assertEquals(1, entries.size)
        assertEquals("0.1.0-dev.1", entries.single().version)
        assertTrue(ChangelogParser.hasChangesFor(entries, null, listOf("Facebook")))
        assertTrue(ChangelogParser.hasChangesFor(entries, null, listOf("Messenger")))
    }
}
