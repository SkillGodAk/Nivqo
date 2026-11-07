package app.morphe.manager.domain.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NivqoOnlyUpdateSourcesTest {
    @Test
    fun cleanInstallStartsWithOnlyOurTwoPatchChannels() {
        val defaults = PatchBundleRepository.createDefaultEntities(1L)
        assertEquals(2, defaults.size)
        assertEquals(
            setOf(PatchBundleRepository.HUSHFACEBOOK_SOURCE_UID, PatchBundleRepository.HUSHMESSENGER_SOURCE_UID),
            defaults.map { it.uid }.toSet(),
        )
    }

    @Test
    fun legacyMorpheAndArbitrarySourceCannotNotify() {
        val allows: (Int, String) -> Boolean = { uid, endpoint -> PatchBundleRepository.isNivqoUpdateNotificationSource(uid, endpoint) }
        assertFalse(allows(PatchBundleRepository.DEFAULT_SOURCE_UID, "https://api.morphe.software/v2/patches"))
        assertFalse(allows(1234, "https://github.com/MorpheApp/morphe-patches"))
        assertFalse(allows(1234, "https://raw.githubusercontent.com/MorpheApp/morphe-patches/main/patches-bundle.json"))
        assertFalse(allows(1234, "https://evil.example/SkillGodAk/Nivqo/main/patches-bundle.json"))
        assertFalse(allows(1234, "http://raw.githubusercontent.com/SkillGodAk/Nivqo/main/patches-bundle.json"))
    }

    @Test
    fun onlyNivqoControlledEndpointsGenerateAutomaticPatchUpdateAlerts() {
        val allows: (Int, String) -> Boolean = { uid, endpoint -> PatchBundleRepository.isNivqoUpdateNotificationSource(uid, endpoint) }
        assertTrue(allows(PatchBundleRepository.HUSHFACEBOOK_SOURCE_UID, PatchBundleRepository.HUSHFACEBOOK_UPDATE_ENDPOINT))
        assertTrue(allows(PatchBundleRepository.HUSHMESSENGER_SOURCE_UID, PatchBundleRepository.HUSHMESSENGER_UPDATE_ENDPOINT))
        assertTrue(allows(1234, "https://raw.githubusercontent.com/SkillGodAk/Nivqo/main/patches-bundle.json"))
        assertTrue(allows(1234, "https://github.com/SkillGodAk/Nivqo"))
        assertFalse(allows(PatchBundleRepository.HUSHFACEBOOK_SOURCE_UID, "https://github.com/MorpheApp/morphe-patches"))
    }
}
