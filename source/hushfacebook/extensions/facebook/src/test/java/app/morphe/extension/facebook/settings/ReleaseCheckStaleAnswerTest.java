/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * The status card after an update, before the next try: the kept answer names an older release and
 * the Facebook build that one targeted. Seen on a phone (2026-10-10): Hushfacebook 0.9.0 on Facebook
 * 582 said "Hushfacebook 0.7.1 targets Facebook 582.0.0.50.54", from a try made under 0.7.1.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReleaseCheckStaleAnswerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test
    public void anAnswerOlderThanTheRunningReleaseSaysNothingOfItsFacebook() {
        assertNull(ReleaseCheck.statusLine("0.7.1", "582.0.0.50.54", "0.9.0", "583.0.0.39.55"));
        assertNull("nor without a target", ReleaseCheck.statusLine("0.7.1", "", "0.9.0", "583.0.0.39.55"));
    }

    @Test
    public void theRunningReleaseStillNamesAnotherFacebook() {
        assertEquals("Hushfacebook " + L10n.isolate("0.9.0") + " targets Facebook " + L10n.isolate("583.0.0.39.55") + ".",
                ReleaseCheck.statusLine("0.9.0", "583.0.0.39.55", "0.9.0", "582.0.0.50.54"));
    }

    @Test
    public void aNewerReleaseIsStillNamedWithItsFacebook() {
        assertEquals("Hushfacebook " + L10n.isolate("0.10.0") + " is out. Update it in Morphe Manager. It targets Facebook "
                        + L10n.isolate("584.0.0.1.2") + ".",
                ReleaseCheck.statusLine("0.10.0", "584.0.0.1.2", "0.9.0", "583.0.0.39.55"));
    }

    @Test
    public void aRunningVersionThisCantReadStillNamesAnotherFacebook() {
        assertEquals("Hushfacebook " + L10n.isolate("0.9.0") + " targets Facebook " + L10n.isolate("583.0.0.39.55") + ".",
                ReleaseCheck.statusLine("0.9.0", "583.0.0.39.55", "", "582.0.0.50.54"));
    }
}
