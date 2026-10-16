package app.hushmessenger.patches.coexist

import app.hushmessenger.patches.MessengerTarget
import app.morphe.patcher.patch.PatchException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Every supported 583 build as a failure names them, in VERSIONS order. */
internal val CODES_583 = MessengerTarget.VERSIONS.getValue("583.0.0.29.95").joinToString(" or ")

class DexSiteContractTest {
    private fun assertActionable(failure: PatchException) {
        assertContains(failure.message.orEmpty(), "Use an unmodified arm64 Messenger 583.0.0.29.95 APK")
        assertContains(failure.message.orEmpty(), "version code $CODES_583)")
    }

    @Test
    fun acceptsTheSupportedInstructionSites() {
        validateDexSites(expectedDexSites.toList())
    }

    @Test
    fun eachBuildAcceptsOnlyItsOwnInstructionSites() {
        assertEquals(MessengerTarget.VERSION_CODES.toSet(), expectedDexSitesByBuild.keys)
        validateDexSites(expectedDexSites346611351.toList(), expectedDexSitesFor("346611351"))
        assertFailsWith<PatchException> { validateDexSites(expectedDexSites346611351.toList(), expectedDexSitesFor("346611369")) }
        assertFailsWith<PatchException> { validateDexSites(expectedDexSites.toList(), expectedDexSitesFor("346611351")) }
    }

    @Test
    fun aSecondVersionNameUsesItsOwnBuildsSitesAndIsNamedInFailures() {
        val versions = MessengerTarget.VERSIONS + ("584.0.0.1.91" to listOf(347000001))
        val sites = expectedDexSitesByBuild + (347000001 to expectedDexSites346611351)
        validateVersionCode("347000001", versions)
        assertFailsWith<PatchException> { validateVersionCode("347000001") }
        validateDexSites(expectedDexSites346611351.toList(), expectedDexSitesFor("347000001", sites), versions)
        val failure = assertFailsWith<PatchException> {
            validateDexSites(expectedDexSites.toList(), expectedDexSitesFor("347000001", sites), versions)
        }
        assertContains(failure.message.orEmpty(),
            "Use an unmodified arm64 Messenger ${MessengerTarget.supportedApks()} or 584.0.0.1.91 APK (version code 347000001).")
    }

    @Test
    fun rejectsAMissingInstructionSite() {
        val failure = assertFailsWith<PatchException> {
            validateDexSites(expectedDexSites.toList().dropLast(1))
        }
        assertContains(failure.message.orEmpty(), "expected 6 permission loads")
        assertActionable(failure)
    }

    @Test
    fun rejectsAChangedLiteralWithTheSameLoadCount() {
        val changed = expectedDexSites.toList().toMutableList()
        changed[0] = changed[0].first to "com.facebook.receiver.permission.ACCESS"

        val failure = assertFailsWith<PatchException> { validateDexSites(changed) }
        assertContains(failure.message.orEmpty(), "instruction sites differ")
        assertActionable(failure)
    }

    @Test
    fun rejectsAChangedMethodWithTheSameLiteral() {
        val changed = expectedDexSites.toList().toMutableList()
        changed[0] = changed[0].first.let { "${it.substringBeforeLast('@')}@${it.substringAfterLast('@').toInt() + 1}" } to changed[0].second

        val failure = assertFailsWith<PatchException> { validateDexSites(changed) }
        assertContains(failure.message.orEmpty(), "instruction sites differ")
        assertActionable(failure)
    }
}
