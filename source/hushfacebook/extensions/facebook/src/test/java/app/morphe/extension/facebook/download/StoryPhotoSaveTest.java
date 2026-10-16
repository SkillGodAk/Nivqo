/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.facebook.graphservice.tree.TreeJNI;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Save photo on the story menu (#38): titled only for a photo Story with music while Save any
 * story is on, and a tap on it saves the photo the Story was made from, while a tap on Save never
 * reaches that photo. Saves start against private addresses, so none opens a socket; the hosts the
 * save looked up say which address it took.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StoryPhotoSaveTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String VIDEO_HOST = "video-iad3-1.xx.fbcdn.net";
    private static final String PHOTO_HOST = "scontent-iad3-2.xx.fbcdn.net";
    private static final String VIDEO = "https://" + VIDEO_HOST + "/v/t42.3356-2/story_photo_to_video.mp4?_nc_cat=1&oe=66F0A1B2";
    private static final String PHOTO = "https://" + PHOTO_HOST + "/v/t39.30808-6/story_photo_original.jpg?_nc_cat=1&oe=66F0A1B2";

    private final List<String> looked = Collections.synchronizedList(new ArrayList<>());
    private final List<PostDetails> saved = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void setUp() {
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> {
            looked.add(host);
            return new InetAddress[] { InetAddress.getByName("10.9.8.7") };
        });
        MediaDownload.detailsForTests = saved::add;
    }

    @After
    public void tearDown() throws InterruptedException {
        settle();
        MediaDownload.policyForTests = null;
        MediaDownload.detailsForTests = null;
        PauseForTests.resume();
        Settings.DOWNLOAD_STORIES.resetToDefault();
    }

    private static void settle() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (MediaDownload.savesInFlight() > 0) {
            assertTrue("a save never finished", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    /**
     * A photo Story with music's card as Save and Save photo read it: its kept {@code getMedia()}
     * answers what Facebook shows, the video made from the photo, and the media model it cached
     * holds that video and the photo it was made from.
     */
    public static final class MusicPhotoCard {
        final String id = "1234567890";
        final TreeJNI story = new TreeJNI().with("creation_time", 1_756_728_000L)
                .with(StoryPhoto.MADE_FROM_PHOTO, new TreeJNI());
        final TreeJNI media = new TreeJNI().with("playable_url", VIDEO).with("stories_photo_uri", new TreeJNI().with("uri", PHOTO));
        private final Shown shown = new Shown();

        public Shown getMedia() {
            return shown;
        }
    }

    public static final class Shown {
        final String id = "1234567890";
        final String playableUrl = VIDEO;
    }

    private static MusicPhotoCard musicPhoto() {
        return new MusicPhotoCard();
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    @Test
    public void theMenuTitlesSavePhotoOnlyForAPhotoStoryWithMusicWhileTheSwitchIsOn() {
        assertTrue("the switch starts on", Settings.DOWNLOAD_STORIES.get());
        assertEquals("Save photo", StoryPhoto.menuTitle(musicPhoto()));

        TreeJNI video = new TreeJNI().with("playable_url", VIDEO);
        assertNull("a video Story keeps its single Save", StoryPhoto.menuTitle(new StoryPhotoTest.Card(new TreeJNI(), video)));
        TreeJNI covered = new TreeJNI().with("playable_url", VIDEO).with("stories_photo_uri", new TreeJNI().with("uri", PHOTO));
        assertNull("so does a video Story with a cover photo", StoryPhoto.menuTitle(new StoryPhotoTest.Card(new TreeJNI(), covered)));
        assertNull(StoryPhoto.menuTitle(null));

        Settings.DOWNLOAD_STORIES.save(false);
        assertNull("off, the menu is Facebook's", StoryPhoto.menuTitle(musicPhoto()));
        Settings.DOWNLOAD_STORIES.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertNull("paused, the menu is Facebook's", StoryPhoto.menuTitle(musicPhoto()));
    }

    @Test
    public void aTapOnSavePhotoSavesThePhotoTheStoryWasMadeFrom() throws InterruptedException {
        MusicPhotoCard card = musicPhoto();
        Object item = new Object();
        StoryPhoto.offered(item);

        assertTrue(MediaDownload.saveStory(context(), card, item));
        settle();
        assertEquals("one save went to the downloader", 1, saved.size());
        assertTrue("the save took the photo: " + looked, looked.contains(PHOTO_HOST));
        assertFalse("and not the video: " + looked, looked.contains(VIDEO_HOST));
    }

    @Test
    public void aTapOnSaveNeverTakesThePhoto() throws InterruptedException {
        assertTrue(MediaDownload.saveStory(context(), musicPhoto(), new Object()));
        settle();
        assertTrue(MediaDownload.saveStory(context(), musicPhoto(), null));
        settle();
        assertEquals("both taps saved", 2, saved.size());
        assertTrue("Save took the video: " + looked, looked.contains(VIDEO_HOST));
        assertFalse("Save went to the photo: " + looked, looked.contains(PHOTO_HOST));
    }

    @Test
    public void aTapAfterTheSwitchWentOffSavesNothing() throws InterruptedException {
        Object item = new Object();
        StoryPhoto.offered(item);
        Settings.DOWNLOAD_STORIES.save(false);

        assertTrue("the item is Hushfacebook's, so Facebook's save doesn't run either",
                MediaDownload.saveStory(context(), musicPhoto(), item));
        settle();
        assertEquals(0, saved.size());
        assertTrue(looked.isEmpty());
    }

    @Test
    public void onlyTheItemsTheMenuBuiltAreSavePhoto() {
        Object item = new Object();
        StoryPhoto.offered(item);
        StoryPhoto.offered(null);
        assertTrue(StoryPhoto.isOffered(item));
        assertFalse(StoryPhoto.isOffered(new Object()));
        assertFalse(StoryPhoto.isOffered(null));

        // Told apart by identity: an item class that calls every item equal can't pass Save for Save photo.
        Object photo = new AllEqual();
        StoryPhoto.offered(photo);
        assertTrue(StoryPhoto.isOffered(photo));
        assertFalse(StoryPhoto.isOffered(new AllEqual()));
    }

    /** A menu item whose class compares by fields and finds every pair equal. */
    private static final class AllEqual {
        @Override
        public boolean equals(Object other) {
            return other instanceof AllEqual;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
