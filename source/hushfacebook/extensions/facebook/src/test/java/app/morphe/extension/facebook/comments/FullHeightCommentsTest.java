/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Open comments at full height (#179): with its switch on, a sheet asked to open part way up opens
 * at one, the full height, each split-screen launcher is told to answer no, and a full-height sheet
 * is told it fills the screen, each counted. Facebook's own full height, and any share that isn't a
 * fraction, come back as they were. A sheet's header holding a video, the reel above its comments,
 * is hidden, at once or at the first layout after the video lands, and so is a watch-and-comment
 * sheet's header over the reel playing behind it, while a post's header stays. Off, paused, or
 * before the settings are ready, nothing changes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FullHeightCommentsTest {
    /** The share of the screen a reel's comment sheet asks for on 583. */
    private static final float REEL_SHEET = 0.66f;

    /** The header's id in 583, which the patch reads from Facebook's comments helper. */
    private static final int HEADER = 0x7f0a0612;

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    /** A sheet's views as Facebook's comments helper gets them: the header over the comment list. */
    private static ViewGroup sheet(View inHeader) {
        LinearLayout content = new LinearLayout(context());
        FrameLayout header = new FrameLayout(context());
        header.setId(HEADER);
        header.addView(inHeader);
        content.addView(header);
        content.addView(new FrameLayout(context()));
        return content;
    }

    /** A video a few hosts down, the way Litho mounts the reel's player in the header. */
    private static View hostedVideo() {
        FrameLayout outer = new FrameLayout(context());
        FrameLayout inner = new FrameLayout(context());
        inner.addView(new TextureView(context()));
        outer.addView(inner);
        return outer;
    }

    private static View header(ViewGroup sheet) {
        return sheet.findViewById(HEADER);
    }

    private static void layOut(ViewGroup sheet) {
        header(sheet).getViewTreeObserver().dispatchOnGlobalLayout();
    }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.OPEN_COMMENTS_FULL_HEIGHT.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.FULL_HEIGHT_COMMENTS + ":")) return line;
        }
        return null;
    }

    @Test
    public void onAPartSheetOpensAtFullHeightAndTheSplitScreenIsPassedOverEachCounted() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        assertEquals("a reel's sheet stayed part way up", 1f, FullHeightComments.sheetHeight(REEL_SHEET), 0f);
        assertEquals("a server share stayed part way up", 1f, FullHeightComments.sheetHeight(0.75f), 0f);
        assertEquals("Facebook's full height changed", 1f, FullHeightComments.sheetHeight(1f), 0f);
        assertEquals("a zero share was raised", 0f, FullHeightComments.sheetHeight(0f), 0f);
        assertEquals("a share over one was lowered", 1.5f, FullHeightComments.sheetHeight(1.5f), 0f);
        assertTrue("the split screen opened", FullHeightComments.skipSplitScreen());
        assertTrue("a full-height sheet was drawn as a box", FullHeightComments.fillsScreen(1f));
        assertFalse("a part sheet was made full screen", FullHeightComments.fillsScreen(REEL_SHEET));
        assertEquals(FamilyNames.FULL_HEIGHT_COMMENTS + ": invoked 8, 3 found, 0 missing. Counted: "
                + FullHeightComments.SHEET_RAISED + " 2, " + FullHeightComments.SPLIT_SCREEN_SKIPPED + " 1, "
                + FullHeightComments.SCREEN_FILLED + " 1",
                statusLine());
    }

    @Test
    public void onTheVideoAboveASheetsCommentsIsHiddenAndCounted() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        ViewGroup sheet = sheet(hostedVideo());
        FullHeightComments.watchHeader(sheet, HEADER, false);
        assertEquals("the reel above the comments still shows", View.GONE, header(sheet).getVisibility());
        assertEquals("the comment list was hidden", View.VISIBLE, sheet.getChildAt(1).getVisibility());
        assertEquals(FamilyNames.FULL_HEIGHT_COMMENTS + ": invoked 1, 1 found, 0 missing. Counted: "
                + FullHeightComments.VIDEO_HIDDEN + " 1", statusLine());
    }

    @Test
    public void aVideoThatLandsAfterTheSetupIsHiddenAtTheNextLayout() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        FrameLayout host = new FrameLayout(context());
        ViewGroup sheet = sheet(host);
        FullHeightComments.watchHeader(sheet, HEADER, false);
        assertEquals("a header without a video was hidden", View.VISIBLE, header(sheet).getVisibility());
        host.addView(new SurfaceView(context()));
        layOut(sheet);
        assertEquals("the video that landed later still shows", View.GONE, header(sheet).getVisibility());
    }

    @Test
    public void aWatchAndCommentSheetsSeeThroughHeaderIsHiddenAndCounted() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        // The Reels tab's sheet: the header is an empty tap area over the reel playing behind it.
        ViewGroup sheet = sheet(new FrameLayout(context()));
        FullHeightComments.watchHeader(sheet, HEADER, true);
        assertEquals("the reel behind the sheet still shows above the comments", View.GONE, header(sheet).getVisibility());
        assertEquals("the comment list was hidden", View.VISIBLE, sheet.getChildAt(1).getVisibility());
        assertEquals(FamilyNames.FULL_HEIGHT_COMMENTS + ": invoked 1, 1 found, 0 missing. Counted: "
                + FullHeightComments.VIDEO_HIDDEN + " 1", statusLine());
    }

    @Test
    public void aWatchAndCommentHeaderShownAgainIsHiddenAtTheNextLayout() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        ViewGroup sheet = sheet(new FrameLayout(context()));
        FullHeightComments.watchHeader(sheet, HEADER, true);
        header(sheet).setVisibility(View.VISIBLE);
        layOut(sheet);
        assertEquals("the header Facebook showed again stayed", View.GONE, header(sheet).getVisibility());
    }

    @Test
    public void offOrPausedAWatchAndCommentSheetKeepsItsHeader() {
        ViewGroup off = sheet(new FrameLayout(context()));
        FullHeightComments.watchHeader(off, HEADER, true);
        layOut(off);
        assertEquals("off, the header over the reel was hidden", View.VISIBLE, header(off).getVisibility());

        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ViewGroup paused = sheet(new FrameLayout(context()));
        FullHeightComments.watchHeader(paused, HEADER, true);
        layOut(paused);
        assertEquals("paused, the header over the reel was hidden", View.VISIBLE, header(paused).getVisibility());
    }

    @Test
    public void aPostsHeaderStays() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        TextView reactions = new TextView(context());
        reactions.setText("You and 23 others");
        ViewGroup sheet = sheet(reactions);
        FullHeightComments.watchHeader(sheet, HEADER, false);
        layOut(sheet);
        assertEquals("a post's reactions were hidden", View.VISIBLE, header(sheet).getVisibility());
    }

    @Test
    public void aSheetSetUpTwiceIsWatchedOnce() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        FrameLayout host = new FrameLayout(context());
        ViewGroup sheet = sheet(host);
        FullHeightComments.watchHeader(sheet, HEADER, false);
        host.addView(new TextureView(context()));
        // The second set-up stops at the header already watched, so the video it now holds waits
        // for the first watch's next layout instead of being hidden by a second one.
        FullHeightComments.watchHeader(sheet, HEADER, false);
        assertEquals(View.VISIBLE, header(sheet).getVisibility());
        layOut(sheet);
        assertEquals(View.GONE, header(sheet).getVisibility());
        assertEquals(FamilyNames.FULL_HEIGHT_COMMENTS + ": invoked 2, 1 found, 0 missing. Counted: "
                + FullHeightComments.VIDEO_HIDDEN + " 1", statusLine());
    }

    @Test
    public void aSheetWithoutTheHeaderOrViewsIsLeftAlone() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        FullHeightComments.watchHeader(null, HEADER, false);
        ViewGroup sheet = sheet(hostedVideo());
        FullHeightComments.watchHeader(sheet, HEADER + 1, false);
        assertEquals(View.VISIBLE, header(sheet).getVisibility());
    }

    @Test
    public void offOrPausedTheVideoAboveTheCommentsStays() {
        ViewGroup off = sheet(hostedVideo());
        FullHeightComments.watchHeader(off, HEADER, false);
        layOut(off);
        assertEquals("off, the reel above the comments was hidden", View.VISIBLE, header(off).getVisibility());

        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ViewGroup paused = sheet(hostedVideo());
        FullHeightComments.watchHeader(paused, HEADER, false);
        layOut(paused);
        assertEquals("paused, the reel above the comments was hidden", View.VISIBLE, header(paused).getVisibility());
    }

    @Test
    public void pausedAfterTheSetupALaterVideoStays() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        FrameLayout host = new FrameLayout(context());
        ViewGroup sheet = sheet(host);
        FullHeightComments.watchHeader(sheet, HEADER, false);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        host.addView(new TextureView(context()));
        layOut(sheet);
        assertEquals(View.VISIBLE, header(sheet).getVisibility());
    }

    @Test
    public void notANumberIsNotFullScreen() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        assertFalse("NaN was made full screen", FullHeightComments.fillsScreen(Float.NaN));
    }

    @Test
    public void notANumberIsLeftAsItCame() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        assertTrue("NaN was changed", Float.isNaN(FullHeightComments.sheetHeight(Float.NaN)));
    }

    @Test
    public void offTheSheetKeepsFacebooksHeightAndTheSplitScreenRuns() {
        assertEquals(REEL_SHEET, FullHeightComments.sheetHeight(REEL_SHEET), 0f);
        assertFalse(FullHeightComments.skipSplitScreen());
        assertFalse(FullHeightComments.fillsScreen(1f));
    }

    @Test
    public void pausedTheSheetKeepsFacebooksHeightAndTheSplitScreenRuns() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(REEL_SHEET, FullHeightComments.sheetHeight(REEL_SHEET), 0f);
        assertFalse(FullHeightComments.skipSplitScreen());
        assertFalse(FullHeightComments.fillsScreen(1f));
    }

    @Test
    public void beforeTheSettingsAreReadyNothingChanges() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        ViewGroup sheet = sheet(hostedVideo());
        SettingsContextRule.withoutContext(() -> {
            assertEquals(REEL_SHEET, FullHeightComments.sheetHeight(REEL_SHEET), 0f);
            assertFalse(FullHeightComments.skipSplitScreen());
            assertFalse(FullHeightComments.fillsScreen(1f));
            FullHeightComments.watchHeader(sheet, HEADER, false);
        });
        assertEquals("a sheet set up before the context lost its header", View.VISIBLE, header(sheet).getVisibility());
    }
}
