package app.morphe.manager.network.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagerReplacementUpdateTest {
    @Test
    fun legacyV132SeesReplacementTransportVersion() {
        assertTrue(
            managerUpdateAvailable(
                installedVersion = "1.32.0",
                installedVersionCode = 39800000L,
                remoteVersion = "1.33.1",
                remoteVersionCode = 39900000L,
            )
        )
    }

    @Test
    fun currentV133SeesReplacementByVersionCode() {
        assertTrue(
            managerUpdateAvailable(
                installedVersion = "1.33.0",
                installedVersionCode = 39856514L,
                remoteVersion = "1.33.1",
                remoteVersionCode = 39900000L,
            )
        )
    }

    @Test
    fun replacementV133DoesNotOfferItselfAgain() {
        assertFalse(
            managerUpdateAvailable(
                installedVersion = "1.33.0",
                installedVersionCode = 39900000L,
                remoteVersion = "1.33.1",
                remoteVersionCode = 39900000L,
            )
        )
    }

    @Test
    fun manifestsWithoutVersionCodeKeepSemanticVersionBehavior() {
        assertTrue(
            managerUpdateAvailable(
                installedVersion = "1.32.0",
                installedVersionCode = 1L,
                remoteVersion = "1.33.0",
                remoteVersionCode = null,
            )
        )
        assertFalse(
            managerUpdateAvailable(
                installedVersion = "1.33.0",
                installedVersionCode = 1L,
                remoteVersion = "1.33.0",
                remoteVersionCode = null,
            )
        )
    }
}
