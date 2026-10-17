/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Play SDR versions of HDR videos (#66). Facebook hands its player a video's DASH manifest as one
 * XML string, and every manifest the player, prefetch and offline saves read from a string goes
 * through one parse method (582: the static method logging "DashManifestHelper2"). Its string
 * comes here first.
 *
 * <p>While the switch is on, each video rendition in HDR is taken out of the manifest when the
 * same manifest has an SDR one at least as tall, so the player's quality choice never sees it and
 * the screen never jumps to HDR brightness, with no loss of resolution. A rendition is HDR when
 * its adaptation set or itself names the PQ or HLG transfer ({@code
 * urn:mpeg:mpegB:cicp:TransferCharacteristics} 16 or 18, the property Facebook writes on every
 * video set), when its full VP9 or AV1 codec string names one of those transfers, or when it's
 * Dolby Vision. An HDR rendition taller than every SDR one stays, and so does every rendition of
 * a video served only in HDR, so nothing loses picture or stops playing; Turn off HDR brightness
 * still tone-maps those. A set left with no rendition goes too.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, Facebook parses the
 * manifest it was given. A change shows from the next video Facebook hasn't parsed yet.
 */
public final class SdrRenditions {
    /** Counted under the patch's name for each HDR rendition taken out for an SDR one. */
    static final String SKIPPED = "HDR rendition skipped for an SDR one";

    /** Counted for each manifest that had HDR renditions and no SDR one as tall: they stay. */
    static final String ONLY_HDR = "HDR rendition kept, no SDR one as tall";

    private static final String FAMILY = FamilyNames.HDR_BRIGHTNESS;

    private static final String TRANSFER_SCHEME = "urn:mpeg:mpegB:cicp:TransferCharacteristics";

    /** H.273 transfer characteristics: 16 is PQ (SMPTE ST 2084), 18 is HLG (ARIB STD-B67). */
    private static final int TRANSFER_PQ = 16;
    private static final int TRANSFER_HLG = 18;

    private static final Pattern ADAPTATION_SET = Pattern.compile("<AdaptationSet\\b[\\s\\S]*?</AdaptationSet\\s*>");
    private static final Pattern REPRESENTATION = Pattern.compile(
            "<Representation\\b(?:[^>]*/>|[^>]*>[\\s\\S]*?</Representation\\s*>)");
    private static final Pattern PROPERTY = Pattern.compile("<(?:Supplemental|Essential)Property\\b[^>]*>");

    private static volatile boolean logged;

    private SdrRenditions() {
    }

    /**
     * Injection point, at the start of Facebook's parse of a manifest string: the manifest to
     * parse in its place. Facebook's own while the switch is off or paused.
     */
    public static String manifest(String manifest) {
        try {
            HookStatus.invoked(FAMILY);
            if (manifest == null || !Utils.settingsReady() || !Settings.PREFER_SDR_RENDITIONS.get()) return manifest;
            HookStatus.bound(FAMILY, "manifest");
            Result result = filter(manifest);
            for (int i = 0; i < result.skipped; i++) HookStatus.counted(FAMILY, SKIPPED);
            for (int i = 0; i < result.keptHdr; i++) HookStatus.counted(FAMILY, ONLY_HDR);
            if (result.skipped > 0 && !logged) {
                logged = true;
                final int skipped = result.skipped;
                Logger.printDebug(() -> "Play SDR versions of HDR videos: " + skipped + " HDR renditions skipped");
            }
            return result.manifest;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "manifest", failure);
            return manifest;
        }
    }

    /** A manifest after [filter], how many HDR renditions it lost and how many it kept. */
    static final class Result {
        final String manifest;
        final int skipped;
        final int keptHdr;

        Result(String manifest, int skipped, int keptHdr) {
            this.manifest = manifest;
            this.skipped = skipped;
            this.keptHdr = keptHdr;
        }
    }

    /** One rendition: where it sits in the manifest, its height (0 when unknown) and whether it's HDR. */
    private static final class Rendition {
        final int start;
        final int end;
        final int height;
        final boolean hdr;

        Rendition(int start, int end, int height, boolean hdr) {
            this.start = start;
            this.end = end;
            this.height = height;
            this.hdr = hdr;
        }
    }

    /** One video adaptation set and its renditions. */
    private static final class VideoSet {
        final int start;
        final int end;
        final List<Rendition> renditions = new ArrayList<>();

        VideoSet(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * [manifest] with each HDR video rendition taken out that has an SDR one at least as tall, and
     * each video set it empties. The same string when there's nothing to take out.
     */
    static Result filter(String manifest) {
        if (manifest.indexOf("<Representation") < 0) return new Result(manifest, 0, 0);
        List<VideoSet> sets = new ArrayList<>();
        int tallestSdr = 0;
        boolean anySdr = false;
        boolean anyHdr = false;
        Matcher setMatcher = ADAPTATION_SET.matcher(manifest);
        while (setMatcher.find()) {
            String set = setMatcher.group();
            int headerEnd = set.indexOf('>');
            if (headerEnd < 0) continue;
            String header = set.substring(0, headerEnd + 1);
            int firstRendition = set.indexOf("<Representation");
            String setOwn = firstRendition < 0 ? set : set.substring(0, firstRendition);
            int setTransfer = transfer(setOwn);
            int setHeight = intAttribute(header, "height");
            boolean videoSet = isVideo(header);
            VideoSet video = new VideoSet(setMatcher.start(), setMatcher.end());
            Matcher renditionMatcher = REPRESENTATION.matcher(set);
            while (renditionMatcher.find()) {
                String rendition = renditionMatcher.group();
                int renditionHeaderEnd = rendition.indexOf('>');
                String renditionHeader = rendition.substring(0, renditionHeaderEnd + 1);
                if (!videoSet && !isVideo(renditionHeader)) continue;
                int ownTransfer = transfer(rendition);
                int transfer = ownTransfer != 0 ? ownTransfer : setTransfer;
                String codecs = attribute(renditionHeader, "codecs");
                if (codecs == null) codecs = attribute(header, "codecs");
                boolean hdr = hdrTransfer(transfer) || (ownTransfer == 0 && hdrCodecs(codecs));
                int height = intAttribute(renditionHeader, "height");
                if (height == 0) height = setHeight;
                video.renditions.add(new Rendition(setMatcher.start() + renditionMatcher.start(),
                        setMatcher.start() + renditionMatcher.end(), height, hdr));
                if (hdr) {
                    anyHdr = true;
                } else {
                    anySdr = true;
                    tallestSdr = Math.max(tallestSdr, height);
                }
            }
            if (!video.renditions.isEmpty()) sets.add(video);
        }
        if (!anyHdr) return new Result(manifest, 0, 0);

        // Spans to cut, in order: whole sets a cut empties, else single renditions.
        List<int[]> cuts = new ArrayList<>();
        int skipped = 0;
        int kept = 0;
        for (VideoSet set : sets) {
            List<int[]> setCuts = new ArrayList<>();
            for (Rendition rendition : set.renditions) {
                if (!rendition.hdr) continue;
                // A rendition of unknown height can't be shown to lose nothing, so it stays.
                if (anySdr && rendition.height > 0 && tallestSdr >= rendition.height) {
                    setCuts.add(new int[]{rendition.start, rendition.end});
                } else {
                    kept++;
                }
            }
            skipped += setCuts.size();
            if (setCuts.isEmpty()) continue;
            if (setCuts.size() == set.renditions.size() && allRenditionsOf(manifest, set)) {
                cuts.add(new int[]{set.start, set.end});
            } else {
                cuts.addAll(setCuts);
            }
        }
        if (cuts.isEmpty()) return new Result(manifest, 0, kept);
        StringBuilder out = new StringBuilder(manifest.length());
        int at = 0;
        for (int[] cut : cuts) {
            out.append(manifest, at, cut[0]);
            at = cut[1];
        }
        out.append(manifest, at, manifest.length());
        return new Result(out.toString(), skipped, kept);
    }

    /** Whether [set]'s renditions are every rendition it has, audio ones of a mixed set included. */
    private static boolean allRenditionsOf(String manifest, VideoSet set) {
        Matcher matcher = REPRESENTATION.matcher(manifest.substring(set.start, set.end));
        int count = 0;
        while (matcher.find()) count++;
        return count == set.renditions.size();
    }

    /** Whether an AdaptationSet or Representation start tag says it holds video. */
    private static boolean isVideo(String header) {
        String contentType = attribute(header, "contentType");
        if (contentType != null) return contentType.equals("video");
        String mimeType = attribute(header, "mimeType");
        return mimeType != null && mimeType.startsWith("video/");
    }

    /**
     * The transfer characteristics a property in [xml] names, the first one found, or 0 when none
     * does. A rendition's own properties sit inside it, its set's before its first rendition.
     */
    static int transfer(String xml) {
        if (xml.indexOf(TRANSFER_SCHEME) < 0) return 0;
        Matcher matcher = PROPERTY.matcher(xml);
        while (matcher.find()) {
            String property = matcher.group();
            if (!TRANSFER_SCHEME.equals(attribute(property, "schemeIdUri"))) continue;
            int value = intAttribute(property, "value");
            if (value > 0) return value;
        }
        return 0;
    }

    static boolean hdrTransfer(int transfer) {
        return transfer == TRANSFER_PQ || transfer == TRANSFER_HLG;
    }

    /**
     * Whether a codecs attribute names an HDR video: Dolby Vision, or a full VP9 or AV1 codec
     * string whose transfer field is PQ or HLG. A short string names no transfer, and a 10-bit
     * profile alone doesn't make a video HDR, so those don't count.
     */
    static boolean hdrCodecs(@Nullable String codecs) {
        if (codecs == null) return false;
        for (String codec : codecs.split(",")) {
            String c = codec.trim().toLowerCase(Locale.ROOT);
            if (c.startsWith("dvh1") || c.startsWith("dvhe") || c.startsWith("dav1") || c.startsWith("dva1")
                    || c.startsWith("dvav")) return true;
            String[] fields = c.split("\\.");
            // vp09.PP.LL.DD.CC.cp.tc.mc.FF and av01.P.LLT.DD.M.CCC.cp.tc.mc.F
            int transferField = c.startsWith("vp09.") ? 6 : c.startsWith("av01.") ? 7 : -1;
            if (transferField < 0 || fields.length <= transferField) continue;
            try {
                if (hdrTransfer(Integer.parseInt(fields[transferField]))) return true;
            } catch (NumberFormatException ignored) {
                // Not a number: not a transfer this knows.
            }
        }
        return false;
    }

    /** The value of [name] in a start tag, or null. */
    @Nullable
    static String attribute(String tag, String name) {
        Pattern pattern;
        synchronized (ATTRIBUTES) {
            pattern = ATTRIBUTES.get(name);
            if (pattern == null) {
                pattern = Pattern.compile("\\s" + Pattern.quote(name) + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
                ATTRIBUTES.put(name, pattern);
            }
        }
        Matcher matcher = pattern.matcher(tag);
        if (!matcher.find()) return null;
        return matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
    }

    /** One pattern per attribute name asked for; only a handful ever are. */
    private static final Map<String, Pattern> ATTRIBUTES = new HashMap<>();

    private static int intAttribute(String tag, String name) {
        String value = attribute(tag, name);
        if (value == null) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }
}
