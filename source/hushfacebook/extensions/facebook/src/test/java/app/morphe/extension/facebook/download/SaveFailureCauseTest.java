/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.provider.MediaStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaExtractor;
import org.robolectric.shadows.ShadowToast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Why a save failed, carried as a cause from where the failure happens to what the person saving
 * reads. The toast at the end of the save and the last save on the Downloads page say the same
 * thing, and a different thing for each kind of failure: no connection, an address the server
 * turned down, a body that was empty or cut short, a phone with no room, a work file that couldn't
 * be written, tracks that wouldn't join and a gallery that said no. A photo, a single video file
 * and a DASH pair each go through it, with notifications on and off. A failure nobody can name
 * keeps the plain answer, and every failed save leaves no row, work file or held slot.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SaveFailureCauseTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String PLAIN = "Download failed. Try again in a moment.";
    private static final String NETWORK = "The connection failed. Check your internet and try again.";
    private static final String EXPIRED = "Link expired. Reopen the item and try again.";
    private static final String SERVER = "Facebook's server didn't send the file. Try again in a moment.";
    private static final String NOT_MEDIA = "Not saved: that isn't a Facebook photo or video";
    private static final String INCOMPLETE = "The download ended before the whole file arrived. Try again.";
    private static final String STORAGE = "Your phone couldn't write the file. Check your free space and try again.";
    private static final String NO_SPACE = "Not enough free space to save this. Free some up and try again.";
    private static final String CONVERSION = "Your phone couldn't put this video together. Try another Download quality.";
    private static final String GALLERY = "The file couldn't be copied into your phone's gallery. Try again in a moment.";

    private static final String META_VIDEO = "https://scontent.xx.fbcdn.net/v.mp4";
    private static final String META_PHOTO = "https://scontent.xx.fbcdn.net/p.jpg";
    private static final byte[] PAGE = "<html><body>Log in</body></html>".getBytes(StandardCharsets.UTF_8);

    /** Offline: no Meta name resolves, so nothing connects. */
    private static final MediaUrlPolicy OFFLINE = new MediaUrlPolicy(host -> {
        throw new java.net.UnknownHostException(host);
    });

    private LocalServer server;
    private String origin;
    private MediaUrlPolicy policy;
    private Context context;
    private MediaSaveTest.Gallery gallery;
    /** What the save a test just ran answered, before it became a toast. */
    private volatile Downloader.Result ended;

    @Before
    public void setUp() throws IOException {
        server = new LocalServer();
        int port = server.port();
        origin = server.origin();
        policy = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        context = RuntimeEnvironment.getApplication();
        MediaDownload.policyForTests = policy;
        gallery = Robolectric.setupContentProvider(MediaSaveTest.Gallery.class, MediaStore.AUTHORITY);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1),
                new ByteArrayOutputStream());
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void tearDown() throws IOException {
        MediaDownload.policyForTests = null;
        DashSave.usableForTests = null;
        // The join case leaves Robolectric's extractor nothing, and the others never reach it.
        ShadowMediaExtractor.reset();
        LogBufferManager.clearLogBuffer();
        server.close();
    }

    private static byte[] mp4(int size) {
        byte[] body = new byte[size];
        byte[] head = { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2' };
        System.arraycopy(head, 0, body, 0, head.length);
        for (int i = head.length; i < size; i++) body[i] = (byte) (i * 13);
        return body;
    }

    private static byte[] jpeg(int size) {
        byte[] body = new byte[size];
        byte[] head = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0 };
        System.arraycopy(head, 0, body, 0, head.length);
        for (int i = head.length; i < size; i++) body[i] = (byte) (i * 7);
        return body;
    }

    /** A single video file at [path], saved on the path every single-file save takes. */
    private MediaDownload.Job video(String path) {
        return MediaDownload.fileJob(context, origin + path, Downloader.Kind.VIDEO);
    }

    /** A photo at [path], saved the way the photo viewer's Save does. */
    private MediaDownload.Job photo(String path) {
        return MediaDownload.fileJob(context, origin + path, Downloader.Kind.IMAGE);
    }

    /** A DASH picture of [size] bytes served at [path]. */
    private DashManifest.Track picture(String path, int size) {
        byte[] body = mp4(size);
        server.serve(path, 200, "video/mp4", body, body.length);
        return new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000, origin + path);
    }

    private static DashManifest.Track track(String url) {
        return new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000, url);
    }

    /** The DASH save of [video] and [audio], then the single file [fallback] when there is one. */
    private MediaDownload.Job dash(DashManifest.Track video, DashManifest.Track audio, String fallback) {
        return MediaDownload.dashJob(context, video, audio, fallback);
    }

    /** Runs [job] as a tap starts a save, to its end, and answers the toast it ended with. */
    private String end(boolean video, MediaDownload.Job job) throws InterruptedException {
        return end(context, video, job);
    }

    private String end(Context application, boolean video, MediaDownload.Job job) throws InterruptedException {
        ShadowToast.reset();
        ended = null;
        Thread worker = MediaDownload.start(application, video, (writer, progress) -> {
            ended = job.run(writer, progress);
            return ended;
        });
        assertNotNull("the save didn't start", worker);
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return String.valueOf(ShadowToast.getTextOfLatestToast());
    }

    private Set<String> pendingList() {
        return new HashSet<>(context.getSharedPreferences("hushfacebook_saves", Context.MODE_PRIVATE)
                .getStringSet("pending_rows", new HashSet<>()));
    }

    /**
     * The save that just ended failed for [cause]: its toast said [words], the last save on the
     * Downloads page says the same and names no address, and nothing of the save is left.
     */
    private void assertFailed(Downloader.Cause cause, String words, boolean video, String toast) {
        assertEquals(String.valueOf(ended), words, toast);
        assertEquals(String.valueOf(ended), cause, ended.cause);
        assertEquals(video ? SaveControl.Outcome.VIDEO_FAILED : SaveControl.Outcome.PHOTO_FAILED,
                SaveControl.lastOutcome());
        assertEquals(cause, SaveControl.lastCause());
        assertEquals(words, SaveControl.lastOutcomeText(context));
        assertFalse(words, words.contains("://"));
        assertNothingIsLeft();
    }

    private void assertNothingIsLeft() {
        assertTrue("a failed save left a gallery row", gallery.rows.isEmpty());
        assertTrue("a failed save left a pending row on the list", pendingList().isEmpty());
        String[] left = DashSave.workFolder(context).list();
        assertEquals("a failed save left work files behind", 0, left == null ? 0 : left.length);
        assertEquals("a failed save kept its slot", 0, MediaDownload.savesInFlight());
    }

    // ------------------------------------------------------------------ one video file

    @Test
    public void aVideoWithNoConnectionSaysToCheckTheInternet() throws Exception {
        MediaDownload.policyForTests = OFFLINE;
        assertFailed(Downloader.Cause.NETWORK, NETWORK, true,
                end(true, MediaDownload.fileJob(context, META_VIDEO, Downloader.Kind.VIDEO)));

        // A name that resolves and a server that isn't there is the network's doing as well.
        MediaDownload.policyForTests = policy;
        server.close();
        assertFailed(Downloader.Cause.NETWORK, NETWORK, true, end(true, video("/gone.mp4")));
    }

    @Test
    public void aVideoTheServerTurnsDownSaysTheLinkExpired() throws Exception {
        for (int code : new int[] { 403, 410 }) {
            server.serve("/old" + code + ".mp4", code, "text/html", PAGE, PAGE.length);
            assertFailed(Downloader.Cause.EXPIRED, EXPIRED, true, end(true, video("/old" + code + ".mp4")));
        }
    }

    @Test
    public void aServerThatAnswersWithoutTheFileIsNotCalledTheNetworkOrTheLink() throws Exception {
        server.serve("/broken.mp4", 500, "text/html", PAGE, PAGE.length);
        assertFailed(Downloader.Cause.SERVER, SERVER, true, end(true, video("/broken.mp4")));

        server.serve("/login.mp4", 200, "text/html", PAGE, PAGE.length);
        assertFailed(Downloader.Cause.NOT_MEDIA, NOT_MEDIA, true, end(true, video("/login.mp4")));
    }

    @Test
    public void anEmptyOrCutShortVideoSaysTheDownloadEndedEarly() throws Exception {
        server.serve("/empty.mp4", 200, "video/mp4", new byte[0], 0);
        assertFailed(Downloader.Cause.INCOMPLETE, INCOMPLETE, true, end(true, video("/empty.mp4")));
        assertEquals(Downloader.Status.WRITE_ERROR, ended.status);

        // Announces 5000 bytes and sends 1000. Whether the read that meets the early end throws or
        // ends quietly is the HTTP stack's choice, so the status isn't held here: the cause is.
        server.serve("/short.mp4", 200, "video/mp4", mp4(1000), 5000);
        assertFailed(Downloader.Cause.INCOMPLETE, INCOMPLETE, true, end(true, video("/short.mp4")));
    }

    @Test
    public void aPhoneWithNoRoomForTheVideoSaysSo() throws Exception {
        byte[] body = mp4(4096);
        server.serve("/v.mp4", 200, "video/mp4", body, body.length);
        DashSave.usableForTests = () -> DashSave.KEEP_FREE;
        assertFailed(Downloader.Cause.NO_SPACE, NO_SPACE, true, end(true, video("/v.mp4")));
        assertEquals("a file with no room for it was fetched", 0, server.hits("/v.mp4"));

        // Room for 1024 bytes, and a stream with no announced length that runs past it.
        DashSave.usableForTests = () -> DashSave.KEEP_FREE + 1024;
        server.serve("/stream.mp4", 200, "video/mp4", body, -1);
        assertFailed(Downloader.Cause.NO_SPACE, NO_SPACE, true, end(true, video("/stream.mp4")));
        assertEquals(Downloader.Status.WRITE_ERROR, ended.status);
    }

    /**
     * A work file that can't be written is the phone's storage, and nothing measured it as full.
     * It gets words of its own: every WRITE_ERROR used to read the same, and calling each of them
     * a full phone would be as wrong.
     */
    @Test
    public void aWorkFileThatCannotBeWrittenIsNotCalledAFullPhone() throws Exception {
        byte[] body = mp4(4096);
        server.serve("/v.mp4", 200, "video/mp4", body, body.length);
        File missing = new File(context.getCacheDir(), "no-such-folder");

        String toast = end(true, (writer, progress) -> Downloader.fetch(origin + "/v.mp4", Downloader.Kind.VIDEO,
                new File(missing, "video.part"), policy, Downloader.MAX_BYTES, progress));

        assertFailed(Downloader.Cause.STORAGE, STORAGE, true, toast);
        assertEquals(Downloader.Status.WRITE_ERROR, ended.status);
        assertNotEquals(NO_SPACE, toast);
        assertThrows(Downloader.StorageFailure.class, () -> DashSave.workFile("video", ".mp4", missing));
    }

    @Test
    public void aGalleryThatRefusesTheVideoSaysSoAndKeepsNoRow() throws Exception {
        byte[] body = mp4(4096);
        server.serve("/v.mp4", 200, "video/mp4", body, body.length);

        // The pending row can't be published.
        gallery.refuseUpdate = true;
        assertFailed(Downloader.Cause.GALLERY, GALLERY, true, end(true, video("/v.mp4")));
        assertEquals(Downloader.Status.WRITE_ERROR, ended.status);
        assertEquals("the save never reached the gallery", 1, gallery.inserts.size());

        // No new entry at all.
        gallery.refuseUpdate = false;
        gallery.refuseInsert = true;
        assertFailed(Downloader.Cause.GALLERY, GALLERY, true, end(true, video("/v.mp4")));
        assertEquals(1, gallery.inserts.size());
    }

    // ------------------------------------------------------------------ a photo

    @Test
    public void aPhotoSaysWhichFailureItWas() throws Exception {
        MediaDownload.policyForTests = OFFLINE;
        assertFailed(Downloader.Cause.NETWORK, NETWORK, false,
                end(false, MediaDownload.fileJob(context, META_PHOTO, Downloader.Kind.IMAGE)));
        MediaDownload.policyForTests = policy;

        server.serve("/old.jpg", 403, "text/html", PAGE, PAGE.length);
        assertFailed(Downloader.Cause.EXPIRED, EXPIRED, false, end(false, photo("/old.jpg")));

        server.serve("/empty.jpg", 200, "image/jpeg", new byte[0], 0);
        assertFailed(Downloader.Cause.INCOMPLETE, INCOMPLETE, false, end(false, photo("/empty.jpg")));

        server.serve("/short.jpg", 200, "image/jpeg", jpeg(1000), 5000);
        assertFailed(Downloader.Cause.INCOMPLETE, INCOMPLETE, false, end(false, photo("/short.jpg")));

        byte[] body = jpeg(4096);
        server.serve("/p.jpg", 200, "image/jpeg", body, body.length);
        DashSave.usableForTests = () -> DashSave.KEEP_FREE;
        assertFailed(Downloader.Cause.NO_SPACE, NO_SPACE, false, end(false, photo("/p.jpg")));
        DashSave.usableForTests = null;

        gallery.refuseInsert = true;
        assertFailed(Downloader.Cause.GALLERY, GALLERY, false, end(false, photo("/p.jpg")));
        assertEquals("the photo never reached the gallery", 1, server.hits("/p.jpg"));
    }

    // ------------------------------------------------------------------ a DASH pair

    @Test
    public void aDashSaveSaysWhichFetchFailureItWas() throws Exception {
        MediaDownload.policyForTests = OFFLINE;
        assertFailed(Downloader.Cause.NETWORK, NETWORK, true, end(true, dash(track(META_VIDEO), null, null)));
        MediaDownload.policyForTests = policy;

        server.serve("/old.mp4", 403, "text/html", PAGE, PAGE.length);
        assertFailed(Downloader.Cause.EXPIRED, EXPIRED, true, end(true, dash(track(origin + "/old.mp4"), null, null)));

        // A good picture, and a sound track cut short.
        DashManifest.Track video = picture("/v.mp4", 4096);
        server.serve("/a.mp4", 200, "audio/mp4", mp4(1000), 5000);
        DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000, origin + "/a.mp4");
        assertFailed(Downloader.Cause.INCOMPLETE, INCOMPLETE, true, end(true, dash(video, audio, null)));

        // The single file is tried after the tracks, and the save says how that one ended.
        server.serve("/single.mp4", 500, "text/html", PAGE, PAGE.length);
        assertFailed(Downloader.Cause.SERVER, SERVER, true,
                end(true, dash(track(origin + "/old.mp4"), null, origin + "/single.mp4")));
        assertEquals(1, server.hits("/single.mp4"));
    }

    /**
     * The tracks fit, and the joined file doesn't: 100,000 bytes free past the reserve, a
     * 50,000-byte picture, and a join that wants that again and its own megabyte of boxes.
     */
    @Test
    public void aDashSaveWithNoRoomToJoinOrConvertSaysSo() throws Exception {
        DashManifest.Track video = picture("/v.mp4", 50_000);
        DashSave.usableForTests = () -> DashSave.KEEP_FREE + 100_000;

        assertFailed(Downloader.Cause.NO_SPACE, NO_SPACE, true, end(true, dash(video, null, null)));
        assertEquals("WRITE_ERROR (not enough free space to join the tracks)", String.valueOf(ended));

        // A picture made H.264 first wants its room before a codec is opened.
        assertFailed(Downloader.Cause.NO_SPACE, NO_SPACE, true, end(true, (writer, progress) -> DashSave.save(context,
                video, null, true, false, writer, policy, MediaDownload.cap(), progress)));
        assertEquals("WRITE_ERROR (not enough free space to convert the picture)", String.valueOf(ended));
    }

    /** Nothing describes the tracks to Robolectric's extractor, so the join fails as a codec's would. */
    @Test
    public void tracksThatCannotBeJoinedSayToTryAnotherQuality() throws Exception {
        DashManifest.Track video = picture("/v.mp4", 60_000);
        byte[] sound = mp4(40_000);
        server.serve("/a.mp4", 200, "audio/mp4", sound, sound.length);
        DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000, origin + "/a.mp4");

        String toast = end(true, dash(video, audio, null));

        assertEquals("WRITE_ERROR (the tracks could not be joined)", String.valueOf(ended));
        assertFailed(Downloader.Cause.CONVERSION, CONVERSION, true, toast);
        assertEquals("the save never reached the join", 1, server.hits("/a.mp4"));
    }

    /** A joined file the gallery won't publish ends the save there, and its single file isn't fetched. */
    @Test
    public void aGalleryThatRefusesAJoinedVideoSaysSoAndKeepsNoRow() throws Exception {
        FragmentedMp4ForTests picture = FragmentedMp4ForTests.picture("vp09", 720, 1280, 1000);
        picture.fragment(0L).add(32, 1000, FragmentedMp4ForTests.SYNC, 0);
        byte[] body = picture.build();
        server.serve("/joined.mp4", 200, "video/mp4", body, body.length);
        server.serve("/fallback.mp4", 200, "video/mp4", mp4(4096), 4096);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "vp09.00.40.08", 720, 1280, 1000,
                origin + "/joined.mp4");
        gallery.refuseUpdate = true;

        String toast = end(true, dash(video, null, origin + "/fallback.mp4"));

        assertFailed(Downloader.Cause.GALLERY, GALLERY, true, toast);
        assertEquals("the join never reached the gallery", 1, gallery.inserts.size());
        assertEquals("a terminal gallery failure fetched the single file", 0, server.hits("/fallback.mp4"));
    }

    // ------------------------------------------------------------------ every path

    /** A cache folder that can't be made stops a photo, a video file and a DASH save the same way. */
    @Test
    public void aSaveWithNoCacheFolderIsAStorageFailureOnEveryPath() throws Exception {
        File blocker = File.createTempFile("not-a-folder", null, context.getCacheDir());
        try {
            // The work folder's parent is a file, so it can't be made.
            Context broken = new ContextWrapper(context) {
                @Override
                public File getCacheDir() {
                    return blocker;
                }
            };
            assertNull(DashSave.workFolder(broken));
            byte[] body = jpeg(4096);
            server.serve("/p.jpg", 200, "image/jpeg", body, body.length);
            DashManifest.Track video = picture("/v.mp4", 4096);

            assertFailed(Downloader.Cause.STORAGE, STORAGE, false, end(broken, false,
                    MediaDownload.fileJob(broken, origin + "/p.jpg", Downloader.Kind.IMAGE)));
            assertFailed(Downloader.Cause.STORAGE, STORAGE, true, end(broken, true,
                    MediaDownload.fileJob(broken, origin + "/v.mp4", Downloader.Kind.VIDEO)));
            assertFailed(Downloader.Cause.STORAGE, STORAGE, true, end(broken, true,
                    MediaDownload.dashJob(broken, video, null, null)));
            assertEquals("a save with nowhere to put the file fetched it", 0,
                    server.hits("/p.jpg") + server.hits("/v.mp4"));
        } finally {
            Downloader.delete(blocker);
        }
    }

    /**
     * With Facebook's notifications off there's no card to read, so the toast and the last save
     * on the Downloads page are all a person gets. They say why on every path.
     */
    @Test
    public void withNotificationsOffEveryPathStillSaysWhy() throws Exception {
        Shadows.shadowOf(context.getSystemService(NotificationManager.class)).setNotificationsEnabled(false);

        server.serve("/old.jpg", 403, "text/html", PAGE, PAGE.length);
        assertFailed(Downloader.Cause.EXPIRED, EXPIRED, false, end(false, photo("/old.jpg")));

        server.serve("/short.mp4", 200, "video/mp4", mp4(1000), 5000);
        assertFailed(Downloader.Cause.INCOMPLETE, INCOMPLETE, true, end(true, video("/short.mp4")));

        DashManifest.Track video = picture("/v.mp4", 50_000);
        DashSave.usableForTests = () -> DashSave.KEEP_FREE + 100_000;
        assertFailed(Downloader.Cause.NO_SPACE, NO_SPACE, true, end(true, dash(video, null, null)));
        DashSave.usableForTests = null;

        assertFailed(Downloader.Cause.CONVERSION, CONVERSION, true, end(true, dash(video, null, null)));

        gallery.refuseInsert = true;
        byte[] body = mp4(4096);
        server.serve("/good.mp4", 200, "video/mp4", body, body.length);
        assertFailed(Downloader.Cause.GALLERY, GALLERY, true, end(true, video("/good.mp4")));
        assertEquals(0, Shadows.shadowOf(context.getSystemService(NotificationManager.class)).getAllNotifications()
                .size());
    }

    // ------------------------------------------------------------------ what isn't known

    /**
     * A failure that names no cause, and a save that throws, keep the plain answer in the toast
     * and the plain line on the Downloads page. Neither borrows the cause of the save before it.
     */
    @Test
    public void aFailureNobodyCanNameKeepsThePlainAnswer() throws Exception {
        MediaDownload.policyForTests = OFFLINE;
        assertFailed(Downloader.Cause.NETWORK, NETWORK, true,
                end(true, MediaDownload.fileJob(context, META_VIDEO, Downloader.Kind.VIDEO)));

        String toast = end(true, (writer, progress) -> Downloader.Result.fail(Downloader.Status.WRITE_ERROR,
                "nothing to save"));
        assertEquals(PLAIN, toast);
        assertEquals(Downloader.Cause.UNKNOWN, SaveControl.lastCause());
        assertEquals("The video wasn't saved. Try again.", SaveControl.lastOutcomeText(context));
        assertNothingIsLeft();

        assertFailed(Downloader.Cause.NETWORK, NETWORK, false,
                end(false, MediaDownload.fileJob(context, META_PHOTO, Downloader.Kind.IMAGE)));
        toast = end(false, (writer, progress) -> {
            throw new IllegalStateException("the job broke");
        });
        assertEquals(PLAIN, toast);
        assertEquals(SaveControl.Outcome.PHOTO_FAILED, SaveControl.lastOutcome());
        assertEquals(Downloader.Cause.UNKNOWN, SaveControl.lastCause());
        assertEquals("The photo wasn't saved. Try again.", SaveControl.lastOutcomeText(context));
        assertNothingIsLeft();
    }

    /** A save that works, or one the person cancels, takes the last failure's reason with it. */
    @Test
    public void aLaterSaveReplacesTheReasonOfTheOneBefore() throws Exception {
        MediaDownload.policyForTests = OFFLINE;
        assertFailed(Downloader.Cause.NETWORK, NETWORK, true,
                end(true, MediaDownload.fileJob(context, META_VIDEO, Downloader.Kind.VIDEO)));

        String toast = end(true, (writer, progress) -> {
            assertTrue(SaveControl.cancel(((SaveControl.Save) progress).id));
            return Downloader.Result.fail(Downloader.Status.CANCELLED, "the person saving cancelled it");
        });
        assertEquals("Save canceled", toast);
        assertEquals(SaveControl.Outcome.CANCELLED, SaveControl.lastOutcome());
        assertNull("a cancelled save kept a failure's cause", SaveControl.lastCause());

        MediaDownload.policyForTests = policy;
        byte[] body = mp4(4096);
        server.serve("/v.mp4", 200, "video/mp4", body, body.length);
        toast = end(true, video("/v.mp4"));
        assertTrue(toast, toast.startsWith("Saved to "));
        assertEquals(SaveControl.Outcome.VIDEO_SAVED, SaveControl.lastOutcome());
        assertNull("a good save kept the last failure's cause", SaveControl.lastCause());
        assertEquals("Video saved", SaveControl.lastOutcomeText(context));
    }

    /** With all three slots busy a tap starts nothing, and what the last save said stays as it was. */
    @Test
    public void aTapTurnedAwayForWantOfASlotLeavesTheLastOutcomeAlone() throws Exception {
        MediaDownload.policyForTests = OFFLINE;
        assertFailed(Downloader.Cause.NETWORK, NETWORK, true,
                end(true, MediaDownload.fileJob(context, META_VIDEO, Downloader.Kind.VIDEO)));

        try (SaveSlotsForTests ignored = SaveSlotsForTests.holdAll(context)) {
            assertNull("a fourth save started", MediaDownload.start(context, true,
                    (writer, progress) -> Downloader.Result.fail(Downloader.Status.EXPIRED, "never run")));
            assertEquals(SaveSlotsForTests.SLOTS, MediaDownload.savesInFlight());
            assertEquals(Downloader.Cause.NETWORK, SaveControl.lastCause());
            assertEquals(NETWORK, SaveControl.lastOutcomeText(context));
        }
        assertEquals(0, MediaDownload.savesInFlight());
    }

    /** Each cause that says anything says something no other does, and none of it is the plain answer. */
    @Test
    public void everyKnownCauseHasWordsOfItsOwn() {
        Set<String> said = new HashSet<>();
        for (Downloader.Cause cause : Downloader.Cause.values()) {
            String words = MediaDownload.failureText(context, cause);
            String toast = MediaDownload.message(context, Downloader.Status.WRITE_ERROR, cause, null, false);
            if (cause == Downloader.Cause.UNKNOWN) {
                assertNull(words);
                assertEquals(PLAIN, toast);
                continue;
            }
            assertNotNull(cause + " says nothing", words);
            assertEquals("the toast and the Downloads page disagree about " + cause, words, toast);
            assertNotEquals(cause + " reads as the plain answer", PLAIN, words);
            assertTrue(cause + " repeats another cause's words: " + words, said.add(words));
        }
        assertNull(MediaDownload.failureText(context, null));
        assertEquals(PLAIN, MediaDownload.message(context, Downloader.Status.WRITE_ERROR, null, null, false));
    }
}
