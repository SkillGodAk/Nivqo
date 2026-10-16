/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The fuzz targets' seeds and saved findings, run in the ordinary suite. A finding the runner
 * minimized goes in tools/fuzz/regressions/[target]/, and from then on every test run replays it.
 */
public class ParserFuzzSeedsTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Before public void tempFolder() throws IOException {
        System.setProperty(Mp4JoinFuzz.TEMP_PROPERTY, temp.newFolder("cases").getPath());
    }

    @After public void clear() {
        System.clearProperty(Mp4JoinFuzz.TEMP_PROPERTY);
    }

    private static byte[] captured() throws IOException {
        try (InputStream in = ParserFuzzSeedsTest.class.getResourceAsStream("meta-public-dash.mpd")) {
            assertNotNull("the captured manifest is missing", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) > 0; ) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    @Test public void everyManifestSeedPassesTheTarget() throws IOException {
        Map<String, byte[]> seeds = ParserFuzzSeeds.dash(captured());
        for (Map.Entry<String, byte[]> seed : seeds.entrySet()) {
            try {
                DashManifestFuzz.fuzzerTestOneInput(seed.getValue());
            } catch (Throwable failure) {
                throw new AssertionError("seed " + seed.getKey(), failure);
            }
        }
        // The seeds reach the parts they're there for.
        assertEquals(4, DashManifest.parse(text(seeds, "picture-and-sound")).size());
        assertEquals(1, DashManifest.parse(text(seeds, "type-on-the-set")).size());
        // A size past an int is the most an int holds, and a negative bitrate is none, as a word is.
        DashManifest.Track odd = DashManifest.parse(text(seeds, "odd-numbers")).get(0);
        assertEquals(Integer.MAX_VALUE, odd.width);
        assertEquals(12, odd.height);
        assertEquals(0, odd.bandwidth);
        assertEquals(0, DashManifest.parse(text(seeds, "no-address")).size());
        assertEquals(0, DashManifest.parse(text(seeds, "one-track-too-many")).size());
        assertEquals(9, DashManifest.parse(text(seeds, "captured")).size());
    }

    private static String text(Map<String, byte[]> seeds, String name) {
        return new String(seeds.get(name), StandardCharsets.UTF_8);
    }

    @Test public void everyMp4SeedPassesTheTargetAndTheWholeOnesJoin() throws IOException {
        Map<String, byte[]> seeds = ParserFuzzSeeds.mp4();
        for (Map.Entry<String, byte[]> seed : seeds.entrySet()) {
            try {
                Mp4JoinFuzz.fuzzerTestOneInput(seed.getValue());
            } catch (Throwable failure) {
                throw new AssertionError("seed " + seed.getKey(), failure);
            }
        }
        for (String whole : new String[]{"picture-and-sound", "picture-only", "avc-two-runs", "avc-separate-mdats",
                "avc-mdat-first", "avc-wide-mdat", "avc-absolute-base", "avc-last-box-to-the-end"}) {
            assertTrue(whole + " didn't join", joins(seeds.get(whole)));
        }
        for (Map.Entry<String, byte[]> seed : seeds.entrySet()) {
            String name = seed.getKey();
            if (name.contains("-cut-") || name.equals("run-count-huge") || name.equals("mdat-length-huge")) {
                assertFalse(name + " joined", joins(seed.getValue()));
            }
        }
        try (Stream<Path> left = Files.list(Paths.get(System.getProperty(Mp4JoinFuzz.TEMP_PROPERTY)))) {
            assertEquals("a case left its folder behind", 0, left.count());
        }
    }

    /** Whether [input], unpacked the way the target does, joins rather than being refused. */
    private boolean joins(byte[] input) throws IOException {
        int videoLength = ((input[1] & 0xFF) << 24) | ((input[2] & 0xFF) << 16) | ((input[3] & 0xFF) << 8) | (input[4] & 0xFF);
        File video = temp.newFile();
        Files.write(video.toPath(), Arrays.copyOfRange(input, 5, 5 + videoLength));
        File audio = null;
        if ((input[0] & 1) != 0) {
            audio = temp.newFile();
            Files.write(audio.toPath(), Arrays.copyOfRange(input, 5 + videoLength, input.length));
        }
        try {
            return Mp4Join.join(video, audio, temp.newFile(), Downloader.SILENT);
        } catch (IOException refused) {
            return false;
        }
    }

    /** The runner trusts a clean run only after it finds this bug, so the bug has to be there. */
    @Test public void thePlantedBugIsThereAndTheSeedsMissIt() {
        for (Map.Entry<String, byte[]> seed : ParserFuzzSeeds.faulty().entrySet()) {
            FaultyBoxFuzz.fuzzerTestOneInput(seed.getValue());
        }
        try {
            FaultyBoxFuzz.fuzzerTestOneInput(ParserFuzzSeeds.faultyBug());
            fail("the planted bug is gone");
        } catch (BufferUnderflowException expected) {
            // The runner's self-check looks for this.
        }
    }

    @Test public void savedFindingsStayFixed() throws Throwable {
        Path regressions = repositoryRoot().resolve("tools/fuzz/regressions");
        // Only the targets' own folders are replayed, so a finding saved anywhere else in there
        // would never run.
        if (Files.isDirectory(regressions)) {
            try (Stream<Path> entries = Files.list(regressions)) {
                for (Path entry : (Iterable<Path>) entries::iterator) {
                    String name = entry.getFileName().toString();
                    assertTrue("tools/fuzz/regressions/" + name + " isn't a target's folder, so nothing replays it",
                            Files.isDirectory(entry) && (name.equals(ParserFuzzSeeds.DASH) || name.equals(ParserFuzzSeeds.MP4)));
                }
            }
        }
        replay(regressions.resolve(ParserFuzzSeeds.DASH), DashManifestFuzz::fuzzerTestOneInput);
        replay(regressions.resolve(ParserFuzzSeeds.MP4), Mp4JoinFuzz::fuzzerTestOneInput);
    }

    private interface Target {
        void run(byte[] data) throws Throwable;
    }

    private static void replay(Path folder, Target target) throws Throwable {
        if (!Files.isDirectory(folder)) return;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                try {
                    target.run(Files.readAllBytes(file));
                } catch (Throwable failure) {
                    throw new AssertionError("saved finding " + file.getFileName(), failure);
                }
            }
        }
    }

    private static Path repositoryRoot() {
        for (Path at = Paths.get("").toAbsolutePath(); at != null; at = at.getParent()) {
            if (Files.isRegularFile(at.resolve("settings.gradle.kts")) && Files.isDirectory(at.resolve("extensions"))) return at;
        }
        throw new AssertionError("no repository root above " + Paths.get("").toAbsolutePath());
    }
}
