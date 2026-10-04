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
              "created_at": "2026-10-04T16:23:32",
              "description": "HushFacebook 0.7.1 Nivqo core",
              "changelog_url": "https://raw.githubusercontent.com/SkillGodAk/Nivqo/main/updates/hushfacebook-changelog.md",
              "signature_download_url": "",
              "download_url": "https://raw.githubusercontent.com/SkillGodAk/Nivqo/main/updates/bundles/hushfacebook.mpp",
              "version": "0.7.1-nivqo.1"
            }
        """.trimIndent()

        val asset = json.decodeFromString<MorpheAsset>(manifest)
        assertEquals("0.7.1-nivqo.1", asset.version)
        assertEquals("2026-10-04T16:23:32", asset.createdAt.toString())
        assertEquals(
            "https://raw.githubusercontent.com/SkillGodAk/Nivqo/main/updates/hushfacebook-changelog.md",
            asset.changelogUrl
        )
    }

    @Test
    fun combinedMorpheSourceUsesLocalDateTimeAndParsesScopedChangelog() {
        val manifest = """
            {
              "created_at": "2026-10-02T16:40:46",
              "description": "Nivqo Patches",
              "signature_download_url": "",
              "download_url": "https://raw.githubusercontent.com/SkillGodAk/Nivqo/3ab029fac57ce070fcf1aea6fc76a92db6aa9025/updates/bundles/nivqo-patches.mpp",
              "version": "0.1.0"
            }
        """.trimIndent()

        val asset = json.decodeFromString<MorpheAsset>(manifest)
        assertEquals("0.1.0", asset.version)
        assertEquals("2026-10-02T16:40:46", asset.createdAt.toString())

        val entries = ChangelogParser.parse(
            """
            ## [0.1.0](https://github.com/SkillGodAk/Nivqo/commit/3ab029fac57ce070fcf1aea6fc76a92db6aa9025) (2026-10-02)

            ### New Features

            * **Facebook:** Combined HushFacebook source.
            * **Messenger:** Combined HushMessenger source.
            """.trimIndent()
        )
        assertEquals(1, entries.size)
        assertEquals("0.1.0", entries.single().version)
        assertTrue(ChangelogParser.hasChangesFor(entries, null, listOf("Facebook")))
        assertTrue(ChangelogParser.hasChangesFor(entries, null, listOf("Messenger")))
    }
}
