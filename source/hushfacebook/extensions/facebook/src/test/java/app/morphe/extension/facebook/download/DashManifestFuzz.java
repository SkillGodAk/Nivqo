/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A fuzz target for {@link DashManifest}: any bytes, read as a manifest, and every pick a save can
 * make from what comes back. scripts/fuzz-parsers.ps1 runs it under Jazzer, and
 * {@link ParserFuzzSeedsTest} runs the seeds and saved findings through it in the ordinary suite.
 * It names nothing of Jazzer's, so neither the suite nor the extension carries the fuzzer.
 *
 * <p>{@code parse} answers what it read so far and never throws, so the checks are on the answer:
 * never null, nothing for a manifest over the limits, no more tracks than the limit, and every
 * track a whole one. A pick comes from the list it was given.
 */
public final class DashManifestFuzz {

    private DashManifestFuzz() {}

    private static final DownloadQuality[] QUALITIES;
    static {
        List<DownloadQuality> all = new ArrayList<>(Arrays.asList(DownloadQuality.values()));
        all.add(null);
        QUALITIES = all.toArray(new DownloadQuality[0]);
    }

    public static void fuzzerTestOneInput(byte[] data) {
        String manifest = new String(data, StandardCharsets.UTF_8);
        List<DashManifest.Track> tracks = DashManifest.parse(manifest);
        check(tracks != null, "parse answered null");
        if (!DashManifest.withinLimits(manifest)) check(tracks.isEmpty(), "a manifest over the limits gave tracks");
        check(tracks.size() <= DashManifest.MAX_REPRESENTATIONS, "more tracks than the limit: " + tracks.size());

        Map<DashManifest.Track, Boolean> listed = new IdentityHashMap<>();
        for (DashManifest.Track track : tracks) {
            check(track.mime != null && !track.mime.isEmpty(), "a track with no type");
            check(track.codecs != null, "a track with null codecs");
            check(track.url != null && track.url.toLowerCase(Locale.US).startsWith("https://"), "a track with no https address");
            check(track.width >= 0 && track.height >= 0 && track.bandwidth >= 0 && track.label >= 0,
                    "a negative number on " + track);
            check(track.quality() >= 0, "a negative quality on " + track);
            listed.put(track, Boolean.TRUE);
        }

        for (boolean allowAv1 : new boolean[]{false, true}) {
            from(listed, DashManifest.bestVideo(tracks, allowAv1));
            for (DownloadQuality quality : QUALITIES) {
                from(listed, DashManifest.pickVideo(tracks, allowAv1, quality));
                for (boolean compatible : new boolean[]{false, true}) {
                    for (boolean reencodable : new boolean[]{false, true}) {
                        for (DashManifest.Converter converter : new DashManifest.Converter[]{null, track -> true, track -> false}) {
                            DashManifest.Pick pick = DashManifest.pick(tracks, allowAv1, quality, compatible, reencodable, converter);
                            if (pick == null) continue;
                            check(pick.video != null, "a pick with no picture");
                            from(listed, pick.video);
                            from(listed, pick.audio);
                        }
                    }
                }
            }
        }
        for (DownloadQuality quality : QUALITIES) {
            from(listed, DashManifest.pickCompatibleVideo(tracks, quality));
            from(listed, DashManifest.pickConvertibleVideo(tracks, quality, track -> true));
        }
        from(listed, DashManifest.bestAudio(tracks));
        from(listed, DashManifest.bestCompatibleAudio(tracks));
        from(listed, DashManifest.bestXheAudio(tracks));
        DashManifest.hasSound(tracks);
    }

    private static void from(Map<DashManifest.Track, Boolean> listed, DashManifest.Track picked) {
        check(picked == null || listed.containsKey(picked), "a pick that isn't one of the parsed tracks");
    }

    private static void check(boolean fine, String what) {
        if (!fine) throw new AssertionError(what);
    }
}
