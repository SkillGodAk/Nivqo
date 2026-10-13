/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * A fuzz target for {@link Mp4Join}: any bytes, split into a picture file and an optional sound
 * file, joined into a third. scripts/fuzz-parsers.ps1 runs it under Jazzer, and
 * {@link ParserFuzzSeedsTest} runs the seeds and saved findings through it in the ordinary suite.
 *
 * <p>An input is a flags byte (bit 0: there is a sound file), the picture file's length as a
 * big-endian unsigned int taken modulo what follows, then the two files back to back. A join may
 * refuse with an {@link IOException}. Anything else it throws is a finding, and so is a join that
 * stops though nothing cancelled it, an output over the limit, or one the test reader can't read.
 * Each input's files live in a folder of their own that's gone before the next input.
 */
public final class Mp4JoinFuzz {

    private Mp4JoinFuzz() {}

    /** Where each input's folder goes: the runner's work folder, else the system's temp folder. */
    static final String TEMP_PROPERTY = "hushfacebook.fuzz.temp";
    /** The largest output a join may write, in bytes. */
    static final String MAX_OUTPUT_PROPERTY = "hushfacebook.fuzz.maxOutputBytes";
    private static final long DEFAULT_MAX_OUTPUT = 256L * 1024 * 1024;
    /** Outputs up to this size are read back. A bigger one is only held to the limit. */
    private static final long READ_BACK_LIMIT = 16L * 1024 * 1024;

    private static final Downloader.Progress NEVER_CANCELLED = new Downloader.Progress() {
        @Override public void transferred(long done, long total) {}
        @Override public void reading(Runnable close) {}
        @Override public boolean cancelled() { return false; }
    };

    /** Packs a picture file and an optional sound file the way {@link #fuzzerTestOneInput} reads them. */
    static byte[] input(byte[] video, byte[] audio) {
        byte[] data = new byte[5 + video.length + (audio == null ? 0 : audio.length)];
        data[0] = (byte) (audio == null ? 0 : 1);
        data[1] = (byte) (video.length >>> 24);
        data[2] = (byte) (video.length >>> 16);
        data[3] = (byte) (video.length >>> 8);
        data[4] = (byte) video.length;
        System.arraycopy(video, 0, data, 5, video.length);
        if (audio != null) System.arraycopy(audio, 0, data, 5 + video.length, audio.length);
        return data;
    }

    public static void fuzzerTestOneInput(byte[] data) throws IOException {
        if (data.length < 5) return;
        boolean withAudio = (data[0] & 1) != 0;
        int remaining = data.length - 5;
        long announced = ((data[1] & 0xFFL) << 24) | ((data[2] & 0xFFL) << 16) | ((data[3] & 0xFFL) << 8) | (data[4] & 0xFFL);
        int videoLength = (int) (announced % (remaining + 1L));

        Path folder = Files.createTempDirectory(base(), "case-");
        try {
            File video = write(folder.resolve("video.mp4"), data, 5, videoLength);
            File audio = withAudio ? write(folder.resolve("audio.mp4"), data, 5 + videoLength, remaining - videoLength) : null;
            File out = folder.resolve("joined.mp4").toFile();
            try {
                if (!Mp4Join.join(video, audio, out, NEVER_CANCELLED)) {
                    throw new AssertionError("the join stopped though nothing cancelled it");
                }
            } catch (IOException refused) {
                return;
            }
            long limit = Long.getLong(MAX_OUTPUT_PROPERTY, DEFAULT_MAX_OUTPUT);
            if (out.length() > limit) {
                throw new AssertionError("a " + data.length + " byte input joined into " + out.length() + " bytes, over " + limit);
            }
            if (out.length() <= READ_BACK_LIMIT) PlainMp4ForTests.read(Files.readAllBytes(out.toPath()));
        } finally {
            delete(folder);
        }
    }

    private static Path base() throws IOException {
        String configured = System.getProperty(TEMP_PROPERTY);
        Path base = configured != null ? Paths.get(configured) : Paths.get(System.getProperty("java.io.tmpdir"));
        Files.createDirectories(base);
        return base;
    }

    private static File write(Path path, byte[] data, int from, int length) throws IOException {
        byte[] bytes = new byte[length];
        System.arraycopy(data, from, bytes, 0, length);
        Files.write(path, bytes);
        return path.toFile();
    }

    private static void delete(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
        }
    }
}
