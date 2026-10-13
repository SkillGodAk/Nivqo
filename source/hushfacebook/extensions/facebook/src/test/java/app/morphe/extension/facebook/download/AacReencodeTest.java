/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaCodec;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.SettingsContextRule;

/**
 * The re-encode's loop against Robolectric's codecs: every block of sound the decoder gives goes
 * through the encoder into the new file, a cancel stops it, and a decoder that gives no sound makes
 * no file rather than a silent one. What a real phone's codecs make of xHE-AAC is checked on the
 * phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, shadows = {AacReencodeTest.SoundFile.class})
public class AacReencodeTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    // The DASH save logs its steps, which needs the settings a phone would have.
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Samples the sound file holds, each 370 bytes of xHE-AAC, 21.3 ms apart as at 48 kHz. */
    static final int SAMPLES = 40;

    /** Blocks of PCM the decoder made, and blocks of AAC the encoder made. */
    static final AtomicInteger decoded = new AtomicInteger();
    static final AtomicInteger encoded = new AtomicInteger();

    /** Room no re-encode here comes near. */
    static final long ROOMY = 1L << 30;

    /** What each block of AAC the shadow encoder makes takes in the new file, its table entries with it. */
    static final long BLOCK = 300 + OutputAllowance.PER_SAMPLE;

    /** A sound file of [SAMPLES] samples, one xHE-AAC track at [sampleRate] stereo. */
    @Implements(MediaExtractor.class)
    public static class SoundFile {
        /** What the track says its rate is; the shadow decoder's sound comes out at the same rate. */
        static int sampleRate = 48_000;

        private boolean selected;
        private int at;

        @Implementation
        protected void setDataSource(String path) throws IOException {
            if (!new File(path).getName().startsWith("audio")) throw new IOException("not a sound file: " + path);
        }

        @Implementation
        protected int getTrackCount() {
            return 1;
        }

        @Implementation
        protected MediaFormat getTrackFormat(int index) {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, AacReencode.XHE_OBJECT_TYPE);
            return format;
        }

        @Implementation
        protected void selectTrack(int index) {
            selected = true;
        }

        @Implementation
        protected int readSampleData(ByteBuffer buffer, int offset) {
            if (!selected || at >= SAMPLES) return -1;
            buffer.position(offset);
            buffer.put(new byte[370]);
            return 370;
        }

        @Implementation
        protected boolean advance() {
            at++;
            return at < SAMPLES;
        }

        @Implementation
        protected long getSampleTime() {
            return at >= SAMPLES ? -1 : at * 21_333L;
        }
    }

    private static final class Progress implements Downloader.Progress {
        int cancelAfter = Integer.MAX_VALUE;
        int asked;

        @Override
        public void transferred(long done, long total) {
        }

        @Override
        public void reading(Runnable close) {
        }

        @Override
        public boolean cancelled() {
            return ++asked > cancelAfter;
        }
    }

    @Before
    public void codecs() {
        decoded.set(0);
        encoded.set(0);
        SoundFile.sampleRate = 48_000;
        ShadowMediaCodec.clearCodecs();
        // 1,024 stereo 16-bit frames of PCM for each sample, as an AAC decoder gives.
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_AUDIO_AAC, new ShadowMediaCodec.CodecConfig(4_096, 4_096,
                (in, out) -> {
                    // The shadow runs this for the empty end of stream too, which carries no sound.
                    if (!in.hasRemaining()) return;
                    in.position(in.limit());
                    out.put(new byte[4_096]);
                    decoded.incrementAndGet();
                }));
        // An encoder whose input buffer holds half a decoded block, so each block goes in two parts.
        ShadowMediaCodec.addEncoder(MediaFormat.MIMETYPE_AUDIO_AAC, new ShadowMediaCodec.CodecConfig(2_048, 512,
                (in, out) -> {
                    if (!in.hasRemaining()) return;
                    in.position(in.limit());
                    out.put(new byte[300]);
                    encoded.incrementAndGet();
                }));
    }

    @After
    public void noCodecs() {
        ShadowMediaCodec.clearCodecs();
    }

    private File sound() throws IOException {
        return folder.newFile("audio.mp4");
    }

    @Test
    public void everyBlockOfSoundGoesThroughTheEncoderIntoTheNewFile() throws IOException {
        File out = folder.newFile("sound.m4a");
        assertTrue(AacReencode.reencode(sound(), out, ROOMY, new Progress()));
        assertEquals("blocks decoded", SAMPLES, decoded.get());
        assertEquals("each block goes to the encoder in two parts", 2 * SAMPLES, encoded.get());
        assertTrue("the new file is empty", out.length() > 0);
    }

    /**
     * The re-encode keeps to the room its file was given. Room for every block goes through, and
     * room for 20 ends it at the 21st, before the muxer has that block, with the file inside its
     * room. It used to write whatever the encoder made, into space kept for other saves.
     */
    @Test
    public void aReencodeKeepsToItsRoom() throws IOException {
        File sound = sound();
        File fits = folder.newFile("fits.m4a");
        assertTrue("room for every block wasn't enough",
                AacReencode.reencode(sound, fits, OutputAllowance.BOXES + 2 * SAMPLES * BLOCK, new Progress()));
        assertEquals(2 * SAMPLES, encoded.get());

        encoded.set(0);
        File out = folder.newFile("sound.m4a");
        long room = OutputAllowance.BOXES + 20 * BLOCK;
        OutputAllowance.Exceeded over = assertThrows(OutputAllowance.Exceeded.class,
                () -> AacReencode.reencode(sound, out, room, new Progress()));
        assertTrue(over.getMessage(), over.getMessage().contains(String.valueOf(room)));
        assertTrue("it went on past its room", encoded.get() < 2 * SAMPLES);
        assertEquals("the muxer got a block past the room", 20 * 300, out.length());
    }

    /**
     * A DASH save whose re-encoded sound outgrows its room ends as a write error naming that room,
     * with nothing left in the work folder: the conversion's throw never reaches the caller, and
     * the save's single file is what follows.
     */
    @Test
    public void aSaveWhoseSoundOutgrowsItsRoomFailsAndLeavesNothing() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        try (LocalServer server = new LocalServer()) {
            int port = server.port();
            MediaUrlPolicy policy = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
                @Override
                Refusal refusal(URL url) {
                    if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                    return super.refusal(url);
                }
            };
            byte[] body = new byte[1_000];
            byte[] head = { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2' };
            System.arraycopy(head, 0, body, 0, head.length);
            server.serve("/v.mp4", "video/mp4", body);
            server.serve("/a.mp4", "audio/mp4", body);
            DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 1_200_000,
                    server.origin() + "/v.mp4");
            DashManifest.Track sound = new DashManifest.Track("audio/mp4", "mp4a.40.42", 0, 0, 39_000,
                    server.origin() + "/a.mp4");
            // Room for both fetches and 20 blocks of the new sound, where all of it takes 80.
            long cap = OutputAllowance.BOXES + 20 * BLOCK + 2L * body.length;

            Downloader.Result result = DashSave.save(context, video, sound, false, true,
                    new MediaStoreWriter(context, true), policy, cap, new Progress());

            assertEquals(result.toString(), Downloader.Status.WRITE_ERROR, result.status);
            assertTrue(result.toString(), result.toString().contains("the re-encoded sound outgrew the " + cap + " bytes kept for it"));
            assertTrue("the re-encode never ran", encoded.get() > 0);
            assertTrue("it went on past its room", encoded.get() < 2 * SAMPLES);
            String[] left = DashSave.workFolder(context).list();
            assertEquals("work files were left", 0, left == null ? 0 : left.length);
        }
    }

    /**
     * A long track at 48 kbps gets room for what the encoder makes of it at 160 kbps. Three times
     * the file, the room it used to get, ran out once a track passed a few minutes. A file that
     * doesn't say how long it runs is taken at 32 kbps, which covers anything slower than 160.
     */
    @Test
    public void aLongSlowTrackGetsRoomForWhatItBecomes() {
        long tenMinutes = 600_000_000L;
        long at48 = 48_000 / 8 * 600;
        long made = AacReencode.BIT_RATE / 8 * 600;
        assertTrue("the old room fell short", 3 * at48 < made);
        assertTrue(AacReencode.roomFor(tenMinutes, 48_000, at48) > made + OutputAllowance.BOXES);
        assertTrue(AacReencode.roomFor(-1, 48_000, at48) > made + OutputAllowance.BOXES);
        assertEquals("a short track keeps the old floor", AacReencode.LEAST_ROOM, AacReencode.roomFor(1_000_000L, 48_000, 6_000));
    }

    @Test
    public void aCancelStopsTheReencode() throws IOException {
        Progress progress = new Progress();
        progress.cancelAfter = 5;
        assertFalse(AacReencode.reencode(sound(), folder.newFile("sound.m4a"), ROOMY, progress));
        assertTrue("it went on past the cancel", decoded.get() < SAMPLES);
    }

    /** A decoder that gives no sound at all makes no file: joined, it would be a silent save. */
    @Test
    public void aDecoderThatGivesNoSoundMakesNoFile() throws IOException {
        ShadowMediaCodec.clearCodecs();
        ShadowMediaCodec.addDecoder(MediaFormat.MIMETYPE_AUDIO_AAC, new ShadowMediaCodec.CodecConfig(4_096, 4_096,
                (in, out) -> in.position(in.limit())));
        ShadowMediaCodec.addEncoder(MediaFormat.MIMETYPE_AUDIO_AAC, new ShadowMediaCodec.CodecConfig(2_048, 512,
                (in, out) -> in.position(in.limit())));
        File sound = sound();
        File out = folder.newFile("sound.m4a");
        IOException silent = assertThrows(IOException.class, () -> AacReencode.reencode(sound, out, ROOMY, new Progress()));
        assertEquals("the re-encode made no sound", silent.getMessage());
    }

    /**
     * A decoder that names no sample rate can't have its sound timed. The re-encode says so, where it
     * used to divide by zero and leave the save with a bare "try again".
     */
    @Test
    public void aDecoderWithNoSampleRateFailsWithAReason() throws IOException {
        SoundFile.sampleRate = 0;
        File sound = sound();
        File out = folder.newFile("sound.m4a");
        IOException why = assertThrows(IOException.class, () -> AacReencode.reencode(sound, out, ROOMY, new Progress()));
        assertEquals("the decoder gave no sample rate or channel count", why.getMessage());
    }

    /** A later decoder format that repeats the first passes; one that differs ends the re-encode with a reason. */
    @Test
    public void aChangedDecoderFormatIsRefused() throws IOException {
        AacReencode.sameFormat(48_000, 2, 48_000, 4);
        IOException rate = assertThrows(IOException.class, () -> AacReencode.sameFormat(44_100, 2, 48_000, 4));
        assertEquals("the decoder changed its sound format", rate.getMessage());
        assertThrows(IOException.class, () -> AacReencode.sameFormat(48_000, 1, 48_000, 4));
    }

    @Test
    public void onlyXheAacIsReencoded() {
        assertTrue(AacReencode.isXhe("mp4a.40.42"));
        assertTrue(AacReencode.isXhe(" MP4A.40.42 "));
        assertFalse(AacReencode.isXhe("mp4a.40.2"));
        assertFalse(AacReencode.isXhe("mp4a.40.5"));
        assertFalse(AacReencode.isXhe("mp4a.40.420"));
        assertFalse(AacReencode.isXhe(null));
    }
}
