/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

/** Turn off screen transitions, asked from tests in other packages with the patch in the build. */
public final class ScreenTransitionsForTests {
    private ScreenTransitionsForTests() {
    }

    /**
     * Whether a tab, a panel, a tab strip's page, an inner screen, a post's photo page and the photo
     * viewer asked for with their slide or zoom still slide in.
     */
    public static boolean slides() {
        ScreenTransitions.inBuildForTests = true;
        try {
            return ScreenTransitions.tabStyle(1, 1) == 1 && ScreenTransitions.panelSlides(true)
                    && ScreenTransitions.pageSlides(true) && ScreenTransitions.fragmentAnimation(0x7f01006e) == 0x7f01006e
                    && ScreenTransitions.photoPageLength(250) == 250 && ScreenTransitions.photoPageZoom(null) == null
                    && ScreenTransitions.photoViewerLength(250) == 250;
        } finally {
            ScreenTransitions.inBuildForTests = null;
        }
    }
}
