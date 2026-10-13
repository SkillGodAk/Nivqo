/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.widget.ScrollView;

import app.morphe.extension.facebook.feed.PostSources;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.PauseForTests;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.EnumSet;

import static org.junit.Assert.*;

/** Sources keep the same one-scroll editor as words, without discarding rejected drafts. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SourcesEditorTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private ValueRows.SourcesRow row;
    private AlertDialog dialog;

    @Before public void open() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        PauseForTests.resume();
        Settings.HIDDEN_SOURCES.save("Old choice");
        controller = Robolectric.buildActivity(Activity.class).setup();
        HushfacebookPreferenceFragment page = SettingsL10nTest.pageOf(SettingsL10nTest.show(controller.get()));
        row = (ValueRows.SourcesRow) page.findPreference(Settings.HIDDEN_SOURCES.key);
        assertNotNull(row);
        row.showDialog(null);
        ShadowLooper.idleMainLooper();
        dialog = (AlertDialog) row.getDialog();
    }

    @After public void close() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        controller.close();
        ShadowLooper.idleMainLooper();
        Settings.HIDDEN_SOURCES.resetToDefault();
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        PauseForTests.resume();
    }

    @Test public void rejectedLinesAndCapacityLeaveTheDraftAndSavedListWhole() {
        String tooMany = lines(PostSources.MAX_RULES + 1, "Page ");
        String invalid = "Fine\n" + repeat("x", PostSources.MAX_LENGTH + 1);
        String tooLarge = lines(150, repeat("頁", 70));
        assertTrue(PostSources.count(PostSources.clean(tooLarge)) < PostSources.MAX_RULES);
        for (String typed : new String[]{tooMany, invalid, tooLarge}) {
            row.getEditText().setText(typed);
            String why = HushfacebookPreferenceFragment.sourcesRefusal(typed);
            assertNotNull(why);
            assertSays(why);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertTrue("Save closed a rejected draft", dialog.isShowing());
            assertEquals(typed, row.getEditText().getText().toString());
            assertEquals("Old choice", Settings.HIDDEN_SOURCES.savedValue());
            assertSays(why);
        }
        String fits = " New choice \nnew choice\nhttps://www.facebook.com/profile.php?id=123456\n123456\n";
        row.getEditText().setText(fits);
        assertNull(HushfacebookPreferenceFragment.sourcesRefusal(fits));
        assertSays(HushfacebookPreferenceFragment.sourcesEditorLine(fits));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertEquals(PostSources.clean(fits), Settings.HIDDEN_SOURCES.savedValue());
        assertEquals(2, PostSources.count(Settings.HIDDEN_SOURCES.savedValue()));
    }

    @Test public void cancelAfterARefusalKeepsTheSavedList() {
        row.getEditText().setText(lines(PostSources.MAX_RULES + 1, "Page "));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertEquals("Old choice", Settings.HIDDEN_SOURCES.savedValue());
    }

    @Test public void anEmptyDraftClearsTheList() {
        row.getEditText().setText("");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertEquals("", Settings.HIDDEN_SOURCES.savedValue());
    }

    @Test public void duplicatesRemainAllowedWhenTheRuleCountIsFull() {
        String full = "123456\n" + lines(PostSources.MAX_RULES - 1, "Page ");
        assertEquals(PostSources.MAX_RULES, PostSources.count(PostSources.clean(full)));
        assertNull(HushfacebookPreferenceFragment.sourcesRefusal(full + "\npage 1"));
        assertNull(HushfacebookPreferenceFragment.sourcesRefusal(full
                + "\nhttps://www.facebook.com/profile.php?id=123456"));
        assertNotNull(HushfacebookPreferenceFragment.sourcesRefusal(full + "\nAnother Page"));
    }

    @Test public void feedbackShowsOnlyCountsAndLineNumbersInTheSharedScroll() {
        String typed = "private jasmine\n" + repeat("private cedar", 8);
        row.getEditText().setText(typed);
        String why = HushfacebookPreferenceFragment.sourcesRefusal(typed);
        assertNotNull(why);
        assertTrue(why.contains("Line 2"));
        assertFalse(why.contains("private"));
        row.getEditText().setText("private jasmine");
        String counter = HushfacebookPreferenceFragment.sourcesEditorLine("private jasmine");
        assertTrue(counter.contains("1 of 200"));
        assertFalse(counter.contains("private"));
        assertSays(counter);
        ScrollView scroll = scrollAround(row.getEditText());
        assertNotNull(scroll);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
            assertNotSame(scroll, scrollAround(dialog.getButton(which)));
        }
        assertEquals(Integer.MAX_VALUE, row.getEditText().getMaxLines());
        int flags = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        assertEquals(flags, row.getEditText().getImeOptions() & flags);
    }

    private void assertSays(String text) {
        ArrayList<View> found = new ArrayList<>();
        dialog.getWindow().getDecorView().findViewsWithText(found, text, View.FIND_VIEWS_WITH_TEXT);
        assertFalse("the editor doesn't show its current count or refusal", found.isEmpty());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, found.get(0).getAccessibilityLiveRegion());
    }

    private static ScrollView scrollAround(View view) {
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollView) return (ScrollView) parent;
        }
        return null;
    }

    private static String lines(int count, String prefix) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) text.append(i == 0 ? "" : "\n").append(prefix).append(i);
        return text.toString();
    }

    private static String repeat(String text, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(text);
        return result.toString();
    }
}
