/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.coexist;

import static org.junit.Assert.assertEquals;

import android.content.pm.ApplicationInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import app.morphe.extension.shared.SettingsContextRule;

/**
 * App name on the recent apps screen (#105): Facebook labels its task with its brand string, and
 * the patch hands that label to {@link AppName#taskLabel} first, which answers the app's own label,
 * the App name picked at patch time. Without a label to read, Facebook's stays.
 */
@RunWith(RobolectricTestRunner.class)
public class AppNameTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ApplicationInfo info;
    private CharSequence label;
    private int labelRes;

    @Before
    public void remember() {
        info = RuntimeEnvironment.getApplication().getApplicationInfo();
        label = info.nonLocalizedLabel;
        labelRes = info.labelRes;
    }

    @After
    public void restore() {
        info.nonLocalizedLabel = label;
        info.labelRes = labelRes;
    }

    @Test
    public void theTaskGetsTheAppsLabelInsteadOfFacebooks() {
        info.nonLocalizedLabel = "Hush Böök's";
        assertEquals("Hush Böök's", AppName.taskLabel("Facebook"));
        assertEquals("Hush Böök's", AppName.taskLabel(null));
    }

    @Test
    public void anEmptyLabelLeavesFacebooks() {
        info.nonLocalizedLabel = "";
        info.labelRes = 0;
        assertEquals("Facebook", AppName.taskLabel("Facebook"));
    }
}
