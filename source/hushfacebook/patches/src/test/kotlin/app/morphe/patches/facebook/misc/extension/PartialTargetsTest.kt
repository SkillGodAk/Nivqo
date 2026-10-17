/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.extension

import app.morphe.patcher.patch.PatchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a patch working down a list of separate targets does with a build that has some of them,
 * all of them, or none: the rule [handleTargets] holds four patches to.
 */
class PartialTargetsTest {
    private val targets = listOf("first", "second", "third")

    @Test
    fun `a build with some of the targets goes on and names each missing one in the patch log`() {
        var handled = -1
        val warnings = PatchLogCapture.warnings {
            handled = handleTargets("Fixture patch", "widgets", targets) { target ->
                if (target == "second") null else "$target isn't in this build"
            }
        }
        assertEquals("targets dealt with", 1, handled)
        assertEquals(
            listOf(
                "Fixture patch: first isn't in this build. The patch goes on with the 1 of 3 widgets it found.",
                "Fixture patch: third isn't in this build. The patch goes on with the 1 of 3 widgets it found.",
            ),
            warnings,
        )
    }

    /** The positive control: a build with the whole list writes nothing to the log. */
    @Test
    fun `a build with every target logs nothing`() {
        var handled = -1
        val warnings = PatchLogCapture.warnings {
            handled = handleTargets("Fixture patch", "widgets", targets) { null }
        }
        assertEquals(3, handled)
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `a build with none of the targets stops the patch, naming every one`() {
        val warnings = PatchLogCapture.warnings {
            val refused = assertThrows(PatchException::class.java) {
                handleTargets("Fixture patch", "widgets", targets) { "$it isn't in this build" }
            }
            val message = refused.message.orEmpty()
            assertTrue(message, message.startsWith("Fixture patch: this Facebook build has none of the 3 widgets"))
            targets.forEach { assertTrue(message, message.contains("$it isn't in this build")) }
        }
        assertEquals("a refused patch logs no partial warnings", emptyList<String>(), warnings)
    }

    /** Targets the declared build is known to lack share one plain line. Any other missing one keeps its warning. */
    @Test
    fun `targets known to have left Facebook get one plain line and no warning`() {
        var infos = emptyList<String>()
        var handled = -1
        val warnings = PatchLogCapture.warnings {
            infos = PatchLogCapture.infos {
                handled = handleTargets("Fixture patch", "widgets", targets, expectedMissing = setOf("first", "third")) { target ->
                    if (target == "second") null else "$target isn't in this build"
                }
            }
        }
        assertEquals(1, handled)
        assertEquals(emptyList<String>(), warnings)
        assertEquals(
            listOf(
                "Fixture patch: 2 of the 3 widgets left Facebook before " +
                    "${app.morphe.patches.shared.compat.AppCompatibilities.FACEBOOK_TARGET_VERSION}, which is expected. " +
                    "The patch goes on with the 1 it found.",
            ),
            infos,
        )

        // One expected absence and one not: the plain line counts only the expected one.
        val mixed = PatchLogCapture.warnings {
            infos = PatchLogCapture.infos {
                handleTargets("Fixture patch", "widgets", targets, expectedMissing = setOf("third")) { target ->
                    if (target == "second") null else "$target isn't in this build"
                }
            }
        }
        assertEquals(listOf("Fixture patch: first isn't in this build. The patch goes on with the 1 of 3 widgets it found."), mixed)
        assertEquals(1, infos.size)
        assertTrue(infos.single(), infos.single().startsWith("Fixture patch: 1 of the 3 widgets left Facebook before "))

        // An expected absence that turns up is handled like any other, and says nothing.
        val back = PatchLogCapture.warnings {
            infos = PatchLogCapture.infos {
                handled = handleTargets("Fixture patch", "widgets", targets, expectedMissing = setOf("first")) { null }
            }
        }
        assertEquals(3, handled)
        assertEquals(emptyList<String>(), back)
        assertEquals(emptyList<String>(), infos)

        // A build with none of them still stops the patch, expected or not.
        assertThrows(PatchException::class.java) {
            handleTargets("Fixture patch", "widgets", targets, expectedMissing = targets.toSet()) { "$it isn't in this build" }
        }
    }

    /** The patches do their work in [handleTargets]' callback, so each target is asked about once, in order. */
    @Test
    fun `each target is handled once, in the list's order`() {
        val asked = mutableListOf<String>()
        PatchLogCapture.warnings {
            handleTargets("Fixture patch", "widgets", targets) { target -> asked += target; null }
        }
        assertEquals(targets, asked)
    }

    @Test
    fun `a descriptor is named the way Java writes the class`() {
        assertEquals("com.facebook.ads.AdsScreenshotDetector", javaName("Lcom/facebook/ads/AdsScreenshotDetector;"))
    }
}
