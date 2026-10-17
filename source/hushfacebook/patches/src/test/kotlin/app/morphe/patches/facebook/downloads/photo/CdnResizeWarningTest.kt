/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.photo

import app.morphe.PatchContexts
import app.morphe.patches.facebook.downloads.story.STORY_PATCH
import app.morphe.patches.facebook.downloads.story.STORY_WITHOUT_CDN
import app.morphe.patches.facebook.misc.extension.PatchLogCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The warning a build without Facebook's photo address modifier gets names the patch that went
 * looking for it. Download any story fills the same stub for a story's Save photo (#38), and its
 * warning used to name Download any photo, which may not even be in the build.
 */
class CdnResizeWarningTest {

    @Test
    fun `the story path's warning names Download any story and what its save does instead`() {
        val warnings = PatchLogCapture.warnings { PatchContexts.of(emptyList()).fillCdnResize(STORY_PATCH, STORY_WITHOUT_CDN) }
        assertEquals(warnings.toString(), 1, warnings.size)
        val warning = warnings.single()
        assertTrue(warning, warning.startsWith("$STORY_PATCH: "))
        assertTrue(warning, warning.endsWith(STORY_WITHOUT_CDN))
        assertFalse(warning, warning.contains(PHOTO_PATCH))
    }

    @Test
    fun `Download any photo's own warning still names Download any photo`() {
        val warnings = PatchLogCapture.warnings { PatchContexts.of(emptyList()).fillCdnResize() }
        assertEquals(warnings.toString(), 1, warnings.size)
        val warning = warnings.single()
        assertTrue(warning, warning.startsWith("$PHOTO_PATCH: "))
        assertTrue(warning, warning.endsWith(PHOTO_WITHOUT_CDN))
        assertFalse(warning, warning.contains(STORY_PATCH))
    }
}
