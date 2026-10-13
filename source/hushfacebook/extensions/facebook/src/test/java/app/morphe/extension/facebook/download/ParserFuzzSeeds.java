/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static app.morphe.extension.facebook.download.FragmentedMp4ForTests.DURATION;
import static app.morphe.extension.facebook.download.FragmentedMp4ForTests.FLAGS;
import static app.morphe.extension.facebook.download.FragmentedMp4ForTests.NON_SYNC;
import static app.morphe.extension.facebook.download.FragmentedMp4ForTests.SIZE;
import static app.morphe.extension.facebook.download.FragmentedMp4ForTests.SYNC;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The synthetic seeds the fuzz targets start from: small valid inputs and the damage a server or a
 * cut-off download does to them (cut short, a count far too big, a box length that lies, a time
 * scale of nothing). Built here rather than kept as files, so they follow the test MP4 builder.
 * {@code main} writes them for scripts/fuzz-parsers.ps1, one folder per target.
 */
public final class ParserFuzzSeeds {

    private ParserFuzzSeeds() {}

    /** The fuzz targets by the names the runner and the regressions folder use. */
    static final String DASH = "dash";
    static final String MP4 = "mp4";
    static final String FAULTY = "faulty";

    private static final String VIDEO_SET = "<AdaptationSet id=\"0\" contentType=\"video\">";
    private static final String AUDIO_SET = "<AdaptationSet id=\"1\" contentType=\"audio\">";

    private static String representation(String attributes, String url) {
        return "<Representation " + attributes + "><BaseURL>" + url + "</BaseURL>"
                + "<SegmentBase indexRange=\"827-1014\"><Initialization range=\"0-826\"/></SegmentBase></Representation>";
    }

    private static String mpd(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><MPD type=\"static\"><Period id=\"0\">" + body + "</Period></MPD>";
    }

    /** Manifests, with [captured] (a real one) and two cuts of it when it's given. */
    static Map<String, byte[]> dash(byte[] captured) {
        Map<String, String> seeds = new LinkedHashMap<>();
        String avc = representation("id=\"1v\" bandwidth=\"900000\" codecs=\"avc1.64001F\" mimeType=\"video/mp4\" width=\"720\" height=\"1280\" FBQualityLabel=\"720p\"",
                "https://video.example/v1.mp4?a=1&amp;b=2");
        String vp9 = representation("id=\"2v\" bandwidth=\"500000\" codecs=\"vp09.00.31.08\" mimeType=\"video/mp4\" width=\"540\" height=\"960\" FBQualityLabel=\"540p\"",
                "https://video.example/v2.mp4");
        String av1 = representation("id=\"3v\" bandwidth=\"300000\" codecs=\"av01.0.05M.08\" mimeType=\"video/mp4\" width=\"1080\" height=\"1920\"",
                "https://video.example/v3.mp4");
        String aac = representation("id=\"1a\" bandwidth=\"64000\" codecs=\"mp4a.40.2\" mimeType=\"audio/mp4\"",
                "https://video.example/a1.mp4");
        String xhe = representation("id=\"2a\" bandwidth=\"48000\" codecs=\"mp4a.40.42\" mimeType=\"audio/mp4\"",
                "https://video.example/a2.mp4");
        seeds.put("picture-and-sound", mpd(VIDEO_SET + avc + vp9 + av1 + "</AdaptationSet>" + AUDIO_SET + aac + "</AdaptationSet>"));
        seeds.put("xhe-sound-only", mpd(VIDEO_SET + vp9 + "</AdaptationSet>" + AUDIO_SET + xhe + "</AdaptationSet>"));
        seeds.put("type-on-the-set", mpd("<AdaptationSet mimeType=\"video/mp4\" codecs=\"hvc1.1.6.L93.90\">"
                + representation("width=\"1080\" height=\"1920\" bandwidth=\"1\"", "HTTPS://video.example/h.mp4") + "</AdaptationSet>"));
        seeds.put("odd-numbers", mpd(VIDEO_SET + representation(
                "mimeType=\"video/mp4\" codecs=\"avc1\" width=\"99999999999\" height=\" 12 \" bandwidth=\"-5\" FBQualityLabel=\"1080p60\"",
                "https://video.example/n.mp4") + "</AdaptationSet>"));
        seeds.put("no-address", mpd(VIDEO_SET + "<Representation mimeType=\"video/mp4\" width=\"1\" height=\"1\"/>"
                + representation("mimeType=\"video/mp4\"", "http://video.example/plain.mp4") + "</AdaptationSet>"));
        seeds.put("cut-in-a-track", mpd(VIDEO_SET + avc).substring(0, 220));
        StringBuilder over = new StringBuilder(VIDEO_SET);
        for (int i = 0; i <= DashManifest.MAX_REPRESENTATIONS; i++) over.append(avc);
        seeds.put("one-track-too-many", mpd(over.append("</AdaptationSet>").toString()));

        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (Map.Entry<String, String> seed : seeds.entrySet()) bytes.put(seed.getKey(), seed.getValue().getBytes(StandardCharsets.UTF_8));
        if (captured != null) {
            bytes.put("captured", captured);
            bytes.put("captured-half", Arrays.copyOf(captured, captured.length / 2));
            bytes.put("captured-most", Arrays.copyOf(captured, captured.length - 40));
        }
        return bytes;
    }

    private static FragmentedMp4ForTests vp9Picture() {
        FragmentedMp4ForTests picture = FragmentedMp4ForTests.picture("vp09", 160, 90, 15_360);
        for (int f = 0; f < 2; f++) {
            FragmentedMp4ForTests.Fragment fragment = picture.fragment((long) f * 15_360);
            fragment.tfhdDefaults = true;
            fragment.fields = SIZE;
            for (int i = 0; i < 6; i++) fragment.add(i == 0 ? 60 : 12 + i, 2_560, i == 0 ? SYNC : NON_SYNC, 0);
        }
        return picture;
    }

    private static FragmentedMp4ForTests aacSound() {
        FragmentedMp4ForTests sound = FragmentedMp4ForTests.sound(44_100);
        sound.edits = new long[][] {{0, 1_024, 0x00010000}};
        sound.trexFlags = SYNC;
        FragmentedMp4ForTests.Fragment fragment = sound.fragment(0L);
        fragment.fields = DURATION | SIZE;
        for (int i = 0; i < 10; i++) fragment.add(20 + i, 1_024, SYNC, 0);
        return sound;
    }

    private static FragmentedMp4ForTests avcPicture(java.util.function.Consumer<FragmentedMp4ForTests.Fragment> shape) {
        FragmentedMp4ForTests picture = FragmentedMp4ForTests.picture("avc1", 90, 160, 15_360);
        FragmentedMp4ForTests.Fragment fragment = picture.fragment(0L);
        fragment.fields = DURATION | SIZE | FLAGS;
        for (int i = 0; i < 4; i++) fragment.add(30 + i, 512, i == 0 ? SYNC : NON_SYNC, 0);
        shape.accept(fragment);
        return picture;
    }

    /** Where box [type] starts in [bytes], from its length field, or -1. */
    private static int box(byte[] bytes, String type) {
        byte[] wanted = type.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 4; i + 4 <= bytes.length; i++) {
            for (int k = 0; k < 4; k++) if (bytes[i + k] != wanted[k]) continue outer;
            return i - 4;
        }
        return -1;
    }

    private static byte[] u32(byte[] bytes, int at, long value) {
        byte[] copy = bytes.clone();
        for (int k = 0; k < 4; k++) copy[at + k] = (byte) (value >>> (24 - 8 * k));
        return copy;
    }

    private static byte[] u64(byte[] bytes, int at, long value) {
        return u32(u32(bytes, at, value >>> 32), at + 4, value);
    }

    private static int u32At(byte[] bytes, int at) {
        return ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16) | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
    }

    /** Where the first trun's first sample starts. */
    private static int firstSample(byte[] bytes) {
        int trun = box(bytes, "trun");
        int flags = u32At(bytes, trun + 8);
        return trun + 16 + ((flags & 0x1) != 0 ? 4 : 0) + ((flags & 0x4) != 0 ? 4 : 0);
    }

    /** Picture and sound pairs, packed with {@link Mp4JoinFuzz#input}. */
    static Map<String, byte[]> mp4() {
        byte[] video = vp9Picture().build();
        byte[] audio = aacSound().build();
        Map<String, byte[]> seeds = new LinkedHashMap<>();
        seeds.put("picture-and-sound", Mp4JoinFuzz.input(video, audio));
        seeds.put("picture-only", Mp4JoinFuzz.input(video, null));
        seeds.put("avc-two-runs", Mp4JoinFuzz.input(avcPicture(f -> f.runs = 2).build(), audio));
        seeds.put("avc-separate-mdats", Mp4JoinFuzz.input(avcPicture(f -> { f.runs = 2; f.separateMdats = true; }).build(), null));
        seeds.put("avc-mdat-first", Mp4JoinFuzz.input(avcPicture(f -> f.mdatBeforeMoof = true).build(), null));
        seeds.put("avc-wide-mdat", Mp4JoinFuzz.input(avcPicture(f -> f.extendedMdat = true).build(), audio));
        seeds.put("avc-absolute-base", Mp4JoinFuzz.input(avcPicture(f -> f.explicitBase = true).build(), null));
        FragmentedMp4ForTests toTheEnd = avcPicture(f -> {});
        toTheEnd.lastBoxToTheEnd = true;
        seeds.put("avc-last-box-to-the-end", Mp4JoinFuzz.input(toTheEnd.build(), null));

        for (int cut : new int[]{video.length / 4, video.length / 2, video.length - 3}) {
            seeds.put("picture-cut-at-" + cut, Mp4JoinFuzz.input(Arrays.copyOf(video, cut), audio));
        }
        seeds.put("sound-cut-short", Mp4JoinFuzz.input(video, Arrays.copyOf(audio, audio.length - 7)));
        int trun = box(video, "trun");
        seeds.put("run-count-huge", Mp4JoinFuzz.input(u32(video, trun + 12, 0xFFFFFFFFL), audio));
        seeds.put("run-count-past-the-limit", Mp4JoinFuzz.input(u32(video, trun + 12, 2_000_001), audio));
        int moov = box(video, "moov");
        for (long length : new long[]{0, 1, 7, 0xFFFFFFFFL}) {
            seeds.put("moov-length-" + length, Mp4JoinFuzz.input(u32(video, moov, length), audio));
        }
        seeds.put("mdat-length-huge", Mp4JoinFuzz.input(u32(video, box(video, "mdat"), 0xFFFFFFF0L), audio));
        seeds.put("track-timescale-zero", Mp4JoinFuzz.input(u32(video, box(video, "mdhd") + 20, 0), audio));
        seeds.put("movie-timescale-zero", Mp4JoinFuzz.input(u32(video, box(video, "mvhd") + 20, 0), audio));
        seeds.put("decode-time-huge", Mp4JoinFuzz.input(u64(video, box(video, "tfdt") + 12, Long.MAX_VALUE), audio));
        seeds.put("sound-duration-huge", Mp4JoinFuzz.input(video, u32(audio, firstSample(audio), 0xFFFFFFFFL)));
        seeds.put("sound-edit-past-the-end", Mp4JoinFuzz.input(video, u32(audio, box(audio, "elst") + 20, 0x7FFFFFFFL)));
        return seeds;
    }

    /** Boxes the planted bug never sees: none is a moov. */
    static Map<String, byte[]> faulty() {
        Map<String, byte[]> seeds = new LinkedHashMap<>();
        byte[] ftyp = new FragmentedMp4ForTests.Box().box("ftyp", new FragmentedMp4ForTests.Box().ascii("isom").u32(0).bytes()).bytes();
        seeds.put("ftyp", ftyp);
        seeds.put("ftyp-and-free", new FragmentedMp4ForTests.Box().put(ftyp).box("free", new byte[12]).bytes());
        return seeds;
    }

    /** The planted bug's input: a moov that says it's longer than the file. */
    static byte[] faultyBug() {
        return u32(new FragmentedMp4ForTests.Box().box("moov", new byte[4]).bytes(), 0, 64);
    }

    static Map<String, byte[]> seeds(String target, byte[] captured) {
        switch (target) {
            case DASH: return dash(captured);
            case MP4: return mp4();
            case FAULTY: return faulty();
            default: throw new IllegalArgumentException("no fuzz target " + target);
        }
    }

    /** Writes each target's seeds to [args 0]/[target]/[name]. [args 1], when given, is a real manifest. */
    public static void main(String[] args) throws IOException {
        Path out = Paths.get(args[0]);
        byte[] captured = args.length > 1 ? Files.readAllBytes(Paths.get(args[1])) : null;
        for (String target : new String[]{DASH, MP4, FAULTY}) {
            Path folder = Files.createDirectories(out.resolve(target));
            for (Map.Entry<String, byte[]> seed : seeds(target, captured).entrySet()) {
                Files.write(folder.resolve(seed.getKey()), seed.getValue());
            }
        }
    }
}
