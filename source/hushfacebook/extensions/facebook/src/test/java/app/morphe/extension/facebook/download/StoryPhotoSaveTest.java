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
import android.net.Uri;

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

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
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
    /** The story's own copy as the CDN names it: the most it serves (cstp) and the size this one asks for (ctp). */
    private static final String SIZED = "https://" + PHOTO_HOST
            + "/v/t39.30808-6/story_photo_original.jpg?cstp=mx2048x3640&ctp=s1080x1920&_nc_cat=1&oe=66F0A1B2";
    private static final String FULL_HOST = "scontent-iad3-1.xx.fbcdn.net";
    /** What Facebook's modifier answers for the most: another server's copy at 2048x3640. */
    private static final String FULL_SIZE = "https://" + FULL_HOST
            + "/v/t39.30808-6/story_photo_original.jpg?cstp=mx2048x3640&ctp=s2048x3640&_nc_cat=1&oe=66F0A1B2";
    /** A modifier answer asking for less than the story's own copy. */
    private static final String SMALLER = "https://" + FULL_HOST
            + "/v/t39.30808-6/story_photo_original.jpg?cstp=mx2048x3640&ctp=s720x1280&_nc_cat=1&oe=66F0A1B2";

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
        Settings.SAVE_STORY_PHOTOS.resetToDefault();
        PhotoSave.resizerForTests = null;
        PhotoFormat.readsAvifForTests = null;
        HookStatus.clear();
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
        final TreeJNI media;
        private final Shown shown = new Shown();

        MusicPhotoCard() {
            this(PHOTO);
        }

        MusicPhotoCard(String photo) {
            media = new TreeJNI().with("playable_url", VIDEO).with("stories_photo_uri", new TreeJNI().with("uri", photo));
        }

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

    /**
     * #38's second report: a photo Story with music whose node lacks the tree Facebook asks for
     * plays as a video and got no Save photo. Save photo on more stories offers it on any Story
     * whose media holds a video and a photo, and a tap saves that photo.
     */
    @Test
    public void savePhotoOnMoreStoriesOffersItWhereverTheMediaHoldsAPhoto() throws InterruptedException {
        assertFalse("the switch starts off", Settings.SAVE_STORY_PHOTOS.defaultValue);
        assertTrue(PatchFamily.STORY_DOWNLOAD.switches.contains(Settings.SAVE_STORY_PHOTOS));
        TreeJNI covered = new TreeJNI().with("playable_url", VIDEO).with("stories_photo_uri", new TreeJNI().with("uri", PHOTO));
        StoryPhotoTest.Card unmarked = new StoryPhotoTest.Card(new TreeJNI(), covered);
        assertNull("off, an unmarked Story keeps its single Save", StoryPhoto.menuTitle(unmarked));

        Settings.SAVE_STORY_PHOTOS.save(true);
        assertEquals("Save photo", StoryPhoto.menuTitle(unmarked));
        assertEquals("a marked one still gets it", "Save photo", StoryPhoto.menuTitle(musicPhoto()));
        TreeJNI video = new TreeJNI().with("playable_url", VIDEO);
        assertNull("a video Story with no photo keeps its single Save",
                StoryPhoto.menuTitle(new StoryPhotoTest.Card(new TreeJNI(), video)));
        TreeJNI photoOnly = new TreeJNI().with("stories_photo_uri", new TreeJNI().with("uri", PHOTO));
        assertNull("so does a plain photo Story, whose Save already saves the photo",
                StoryPhoto.menuTitle(new StoryPhotoTest.Card(new TreeJNI(), photoOnly)));

        Object item = new Object();
        StoryPhoto.offered(item);
        assertTrue(MediaDownload.saveStory(context(), unmarked, item));
        settle();
        assertTrue("the save took the photo: " + looked, looked.contains(PHOTO_HOST));
        assertFalse("and not the video: " + looked, looked.contains(VIDEO_HOST));

        Settings.DOWNLOAD_STORIES.save(false);
        assertNull("with Save any story off, neither is offered", StoryPhoto.menuTitle(unmarked));
    }

    /** The photo saves at the biggest size Meta's CDN serves it, as Save photo on a post does. */
    @Test
    public void aTapOnSavePhotoTakesTheCdnsFullSizeCopy() throws InterruptedException {
        PhotoFormat.readsAvifForTests = true;
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        PhotoSave.resizerForTests = (address, width, height) -> {
            asked.add(width + "x" + height);
            return Uri.parse(FULL_SIZE);
        };
        MusicPhotoCard card = new MusicPhotoCard(SIZED);
        Object item = new Object();
        StoryPhoto.offered(item);

        assertTrue(MediaDownload.saveStory(context(), card, item));
        settle();
        assertEquals("asked for the most the address allows", Collections.singletonList("2048x3640"), asked);
        assertTrue("the save took the CDN's full size copy: " + looked, looked.contains(FULL_HOST));
        assertFalse("not the story's own copy: " + looked, looked.contains(PHOTO_HOST));
        assertTrue(statusLine(), statusLine().contains(StoryPhoto.FULL_SIZE + " 1"));
    }

    @Test
    public void aCdnCopyNoBiggerThanTheStorysOwnLeavesItsOwn() throws InterruptedException {
        PhotoFormat.readsAvifForTests = true;
        PhotoSave.resizerForTests = (address, width, height) -> Uri.parse(SMALLER);
        Object item = new Object();
        StoryPhoto.offered(item);

        assertTrue(MediaDownload.saveStory(context(), new MusicPhotoCard(SIZED), item));
        settle();
        assertTrue("the story's own copy went: " + looked, looked.contains(PHOTO_HOST));
        assertFalse(looked.contains(FULL_HOST));
        assertFalse(statusLine().contains(StoryPhoto.FULL_SIZE));
    }

    /** Android 11 can't read the AVIF the CDN sends its bigger copies as, so the story's own copy goes. */
    @Test
    public void withoutAvifTheStorysOwnCopyGoes() throws InterruptedException {
        PhotoFormat.readsAvifForTests = false;
        PhotoSave.resizerForTests = (address, width, height) -> Uri.parse(FULL_SIZE);
        Object item = new Object();
        StoryPhoto.offered(item);

        assertTrue(MediaDownload.saveStory(context(), new MusicPhotoCard(SIZED), item));
        settle();
        assertTrue("the story's own copy went: " + looked, looked.contains(PHOTO_HOST));
        assertFalse(looked.contains(FULL_HOST));
    }

    /**
     * When Facebook's modifier fails on a story's photo, the report names the story save that asked,
     * not Download any photo, which may not even be in the build, and the story's own copy goes.
     */
    @Test
    public void aModifierFailureOnAStorysPhotoIsReportedUnderDownloadAnyStory() throws InterruptedException {
        PhotoFormat.readsAvifForTests = true;
        PhotoSave.resizerForTests = (address, width, height) -> {
            throw new IllegalStateException("the modifier failed");
        };
        Object item = new Object();
        StoryPhoto.offered(item);

        assertTrue(MediaDownload.saveStory(context(), new MusicPhotoCard(SIZED), item));
        settle();
        assertTrue("the story's own copy went: " + looked, looked.contains(PHOTO_HOST));
        assertEquals("one miss under the story's patch: " + HookStatus.report(), 1,
                HookStatus.missing(FamilyNames.STORY_DOWNLOAD).size());
        assertTrue(HookStatus.missing(FamilyNames.STORY_DOWNLOAD).toString(),
                HookStatus.missing(FamilyNames.STORY_DOWNLOAD).get(0).contains("photo address modifier"));
        assertTrue("none under Download any photo: " + HookStatus.report(),
                HookStatus.missing(FamilyNames.PHOTO_DOWNLOAD).isEmpty());

        // A post's Save photo still reports its own failure under Download any photo.
        assertNull(PhotoSave.resized(SIZED));
        assertEquals(1, HookStatus.missing(FamilyNames.PHOTO_DOWNLOAD).size());
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.STORY_DOWNLOAD + ":")) return line;
        }
        return "";
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
