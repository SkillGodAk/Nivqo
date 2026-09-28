/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.network.api

import kotlin.test.*

/**
 * Source manifests point their download links at github.com, which some networks drop while
 * leaving the API reachable. Parsing decides whether that fallback can happen at all, and a link
 * accepted by mistake turns into a pointless API lookup against a 60 per hour quota.
 */
class ReleaseAssetUrlTest {

    @Test
    fun `release download link is parsed`() {
        val ref = parseReleaseAssetUrl(
            "https://github.com/jkennethcarino/adobo/releases/download/v1.3.0/patches-1.3.0.mpp"
        )

        assertNotNull(ref)
        assertEquals("jkennethcarino", ref.owner)
        assertEquals("adobo", ref.repo)
        assertEquals("v1.3.0", ref.tag)
        assertEquals("patches-1.3.0.mpp", ref.fileName)
    }

    @Test
    fun `prerelease tags survive parsing`() {
        val ref = parseReleaseAssetUrl(
            "https://github.com/crimera/piko/releases/download/v3.9.0-dev.7/patches-3.9.0-dev.7.mpp"
        )

        assertNotNull(ref)
        assertEquals("v3.9.0-dev.7", ref.tag)
        assertEquals("patches-3.9.0-dev.7.mpp", ref.fileName)
    }

    @Test
    fun `percent encoded names are offered in both forms`() {
        val ref = parseReleaseAssetUrl(
            "https://github.com/owner/repo/releases/download/v1.0/patches%20v1.mpp"
        )

        assertNotNull(ref)
        assertEquals("patches%20v1.mpp", ref.fileName)
        assertEquals("patches v1.mpp", ref.decodedFileName)
    }

    @Test
    fun `query strings are stripped`() {
        val ref = parseReleaseAssetUrl(
            "https://github.com/owner/repo/releases/download/v1.0/patches.mpp?token=abc"
        )

        assertNotNull(ref)
        assertEquals("patches.mpp", ref.fileName)
    }

    @Test
    fun `non github hosts are rejected`() {
        assertNull(parseReleaseAssetUrl("https://gitlab.com/owner/repo/-/raw/main/patches.mpp"))
        assertNull(parseReleaseAssetUrl("https://raw.githubusercontent.com/owner/repo/main/patches-bundle.json"))
        assertNull(parseReleaseAssetUrl("https://example.com/patches.mpp"))
    }

    @Test
    fun `github links that are not release downloads are rejected`() {
        assertNull(parseReleaseAssetUrl("https://github.com/owner/repo"))
        assertNull(parseReleaseAssetUrl("https://github.com/owner/repo/releases/tag/v1.0"))
        assertNull(parseReleaseAssetUrl("https://github.com/owner/repo/blob/main/patches.mpp"))
        assertNull(parseReleaseAssetUrl("https://github.com/owner/repo/archive/refs/heads/main.zip"))
    }

    @Test
    fun `truncated links are rejected`() {
        assertNull(parseReleaseAssetUrl("https://github.com/owner/repo/releases/download/v1.0"))
        assertNull(parseReleaseAssetUrl("https://github.com/owner/repo/releases/download/v1.0/"))
        assertNull(parseReleaseAssetUrl("https://github.com/"))
        assertNull(parseReleaseAssetUrl(""))
    }

    @Test
    fun `nested asset paths are kept whole`() {
        val ref = parseReleaseAssetUrl(
            "https://github.com/owner/repo/releases/download/v1.0/nested/patches.mpp"
        )

        assertNotNull(ref)
        assertEquals("nested/patches.mpp", ref.fileName)
    }
}
