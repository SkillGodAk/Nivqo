package app.morphe.manager.network.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChangelogUrlTest {
    @Test
    fun rawMainEndpointResolvesSiblingChangelog() {
        assertEquals(
            "https://raw.githubusercontent.com/SkillGodAk/Nivqo/main/CHANGELOG.md",
            changelogUrlFromBundleEndpointUrl(
                "https://raw.githubusercontent.com/SkillGodAk/Nivqo/main/updates/hushfacebook.json"
            )
        )
    }

    @Test
    fun rawRefsHeadsEndpointKeepsWholeReference() {
        assertEquals(
            "https://raw.githubusercontent.com/SkillGodAk/Nivqo/refs/heads/main/CHANGELOG.md",
            changelogUrlFromBundleEndpointUrl(
                "https://raw.githubusercontent.com/SkillGodAk/Nivqo/refs/heads/main/updates/hushfacebook.json"
            )
        )
    }

    @Test
    fun rawRefsTagsEndpointKeepsWholeReference() {
        assertEquals(
            "https://raw.githubusercontent.com/SkillGodAk/Nivqo/refs/tags/v1.33.0/CHANGELOG.md",
            changelogUrlFromBundleEndpointUrl(
                "https://raw.githubusercontent.com/SkillGodAk/Nivqo/refs/tags/v1.33.0/updates/hushfacebook.json"
            )
        )
    }

    @Test
    fun githubBlobEndpointResolvesRawChangelog() {
        assertEquals(
            "https://raw.githubusercontent.com/SkillGodAk/Nivqo/main/CHANGELOG.md",
            changelogUrlFromBundleEndpointUrl(
                "https://github.com/SkillGodAk/Nivqo/blob/main/updates/hushfacebook.json"
            )
        )
    }

    @Test
    fun unknownHostHasNoChangelogInference() {
        assertNull(
            changelogUrlFromBundleEndpointUrl("https://example.com/updates/hushfacebook.json")
        )
    }

    @Test
    fun rawGitHubUrlsGetPerMinuteCacheBuster() {
        assertEquals(
            "https://raw.githubusercontent.com/SkillGodAk/Nivqo/main/updates/hushfacebook.json?t=123",
            rawGitHubCacheBusted(
                "https://raw.githubusercontent.com/SkillGodAk/Nivqo/main/updates/hushfacebook.json",
                minute = 123
            )
        )
        assertEquals(
            "https://raw.githubusercontent.com/SkillGodAk/Nivqo/main/updates/hushfacebook.json?x=1&t=123",
            rawGitHubCacheBusted(
                "https://raw.githubusercontent.com/SkillGodAk/Nivqo/main/updates/hushfacebook.json?x=1",
                minute = 123
            )
        )
        assertEquals(
            "https://example.com/updates/hushfacebook.json",
            rawGitHubCacheBusted("https://example.com/updates/hushfacebook.json", minute = 123)
        )
    }
}
