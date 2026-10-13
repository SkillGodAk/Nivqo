package app.hushmessenger.extension;

import android.content.Intent;
import android.view.View;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 36})
public class SupportRowTest {
    @Before public void reset() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.preferences.edit().clear().commit();
    }

    @Test public void theSupportRowOpensKoFiInTheBrowser() {
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            root.findViewWithTag("tab_app").performClick();
            View support = root.findViewWithTag("support");
            assertEquals("Support HushMessenger. Buy me a coffee on Ko-fi", support.getContentDescription().toString());
            assertTrue(support.isFocusable());
            support.performClick();
            Intent opened = Shadows.shadowOf(screen.get()).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW, opened.getAction());
            assertEquals("https://ko-fi.com/X8K126YVER", opened.getDataString());
            assertTrue(opened.hasCategory(Intent.CATEGORY_BROWSABLE));
        }
    }

    @Test public void withoutABrowserTheRowSaysSo() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        try (var screen = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            View root = screen.get().getWindow().getDecorView();
            root.findViewWithTag("tab_app").performClick();
            root.findViewWithTag("support").performClick();
            assertNull(Shadows.shadowOf(screen.get()).getNextStartedActivity());
            assertEquals("No web browser found on this phone", ShadowToast.getTextOfLatestToast());
        }
    }
}
