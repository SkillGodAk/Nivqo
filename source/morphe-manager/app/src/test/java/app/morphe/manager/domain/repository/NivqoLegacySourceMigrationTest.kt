package app.morphe.manager.domain.repository

import app.morphe.manager.data.room.bundles.PatchBundleEntity
import app.morphe.manager.data.room.bundles.Source
import app.morphe.manager.util.isPatcherOutdated
import io.ktor.http.Url
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NivqoLegacySourceMigrationTest {
    private fun entity(uid: Int, source: Source, name: String = "Morphe Patches") =
        PatchBundleEntity(
            uid = uid,
            name = name,
            source = source,
            sortOrder = 0,
        )

    @Test fun originalMorpheSourceIsRetired() {
        assertTrue(PatchBundleRepository.isRetiredMorpheEntity(entity(0, Source.API)))
        // A restored backup can give the stock Source.API a different UID.
        assertTrue(PatchBundleRepository.isRetiredMorpheEntity(entity(42, Source.API)))
    }

    @Test fun renamedOriginalDefaultSourceIsAlsoRetired() {
        assertTrue(PatchBundleRepository.isRetiredMorpheEntity(
            entity(0, Source.Remote(Url("https://github.com/SkillGodAk/Nivqo")), "Custom name")
        ))
        assertTrue(PatchBundleRepository.isRetiredMorpheEntity(
            entity(15, Source.Remote(Url("https://github.com/MorpheApp/morphe-patches")))
        ))
    }

    @Test fun customSourcesAndNivqoOfficialSourcesRemain() {
        assertFalse(PatchBundleRepository.isRetiredMorpheEntity(
            entity(16, Source.Remote(Url("https://github.com/other-user/other-patches")), "My patches")
        ))
        assertFalse(PatchBundleRepository.isRetiredMorpheEntity(
            entity(PatchBundleRepository.HUSHFACEBOOK_SOURCE_UID,
                Source.Remote(Url(PatchBundleRepository.HUSHFACEBOOK_UPDATE_ENDPOINT)), "HushFacebook")
        ))
        assertFalse(PatchBundleRepository.isRetiredMorpheEntity(
            entity(PatchBundleRepository.HUSHMESSENGER_SOURCE_UID,
                Source.Remote(Url(PatchBundleRepository.HUSHMESSENGER_UPDATE_ENDPOINT)), "HushMessenger")
        ))
        // Name alone does not authorize erasing a user-added unrelated remote source.
        assertFalse(PatchBundleRepository.isRetiredMorpheEntity(
            entity(17, Source.Remote(Url("https://example.com/patched.json")))
        ))
    }

    @Test fun officialNivqoCoreVersionsAreCompatibleWithBundledPatcher() {
        assertFalse(isPatcherOutdated("2.1.0", "2.3.0")) // Facebook & Nivqo combined
        assertFalse(isPatcherOutdated("2.2.0", "2.3.0")) // Messenger
        assertTrue(isPatcherOutdated("2.4.0", "2.3.0")) // Real future incompatibility remains flagged
    }
}