/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Play SDR versions of HDR videos (#66): with the switch on, a manifest's HDR video renditions go
 * when it has an SDR one at least as tall, and nothing else in it changes. Taller HDR, HDR of
 * unknown height and a video served only in HDR stay. Off or paused the manifest goes through as
 * it came.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SdrRenditionsTest {
    private static final HushfacebookPause.Reason[] PAUSES = {
            HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
            HushfacebookPause.Reason.MARKER_FILE};

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
        Settings.PREFER_SDR_RENDITIONS.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.PREFER_SDR_RENDITIONS.resetToDefault();
        HookStatus.clear();
    }

    private static String transfer(int value) {
        return "<SupplementalProperty schemeIdUri=\"urn:mpeg:mpegB:cicp:TransferCharacteristics\" value=\"" + value + "\"/>";
    }

    private static String rendition(String id, String codecs, int height) {
        return "<Representation id=\"" + id + "\" bandwidth=\"100000\" codecs=\"" + codecs + "\" mimeType=\"video/mp4\""
                + (height > 0 ? " width=\"" + height * 9 / 16 + "\" height=\"" + height + "\"" : "")
                + " FBQualityLabel=\"" + height + "p\"><BaseURL>https://video.xx.fbcdn.net/" + id
                + ".mp4</BaseURL><SegmentBase indexRange=\"827-1014\"><Initialization range=\"0-826\"/></SegmentBase></Representation>";
    }

    private static String videoSet(int id, int transfer, String... renditions) {
        return "<AdaptationSet id=\"" + id + "\" contentType=\"video\" subsegmentAlignment=\"true\">"
                + (transfer > 0 ? transfer(transfer) : "") + String.join("", renditions) + "</AdaptationSet>";
    }

    private static final String AUDIO_SET = "<AdaptationSet id=\"9\" contentType=\"audio\">"
            + "<Representation id=\"a\" bandwidth=\"64000\" codecs=\"mp4a.40.5\" mimeType=\"audio/mp4\">"
            + "<BaseURL>https://video.xx.fbcdn.net/a.mp4</BaseURL></Representation></AdaptationSet>";

    private static String mpd(String... sets) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\">"
                + "<Period id=\"0\">" + String.join("", sets) + "</Period></MPD>";
    }

    /** The ids of the Representations left in [manifest], in order. */
    private static List<String> ids(String manifest) {
        List<String> ids = new ArrayList<>();
        Matcher matcher = Pattern.compile("<Representation id=\"([^\"]+)\"").matcher(manifest);
        while (matcher.find()) ids.add(matcher.group(1));
        return ids;
    }

    private static void wellFormed(String manifest) throws Exception {
        InputStream in = new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8));
        assertNotNull(DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in).getDocumentElement());
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.HDR_BRIGHTNESS + ":")) return line;
        }
        return "";
    }

    @Test
    public void anHdrSetGoesWhenAnSdrSetIsAsTall() throws Exception {
        String manifest = mpd(
                videoSet(0, 1, rendition("s720", "vp09.00.31.08.00.01.01.01.00", 720), rendition("s1080", "vp09.00.40.08.00.01.01.01.00", 1080)),
                videoSet(1, 18, rendition("h720", "vp09.02.31.10.01.09.18.09.00", 720), rendition("h1080", "vp09.02.40.10.01.09.18.09.00", 1080)),
                AUDIO_SET);
        String out = SdrRenditions.manifest(manifest);
        assertEquals(Arrays.asList("s720", "s1080", "a"), ids(out));
        assertFalse("the emptied set went too", out.contains("<AdaptationSet id=\"1\""));
        assertTrue(out.contains(AUDIO_SET));
        wellFormed(out);
        String status = statusLine();
        assertTrue(status, status.contains(SdrRenditions.SKIPPED));
    }

    @Test
    public void anHdrRenditionTallerThanEverySdrOneStays() {
        String manifest = mpd(
                videoSet(0, 1, rendition("s720", "av01.0.05M.08.0.111.01.01.01.0", 720)),
                videoSet(1, 16, rendition("h720", "av01.0.05M.10.0.112.09.16.09.0", 720), rendition("h1440", "av01.0.12M.10.0.112.09.16.09.0", 1440)));
        SdrRenditions.Result result = SdrRenditions.filter(manifest);
        assertEquals(Arrays.asList("s720", "h1440"), ids(result.manifest));
        assertEquals(1, result.skipped);
        assertEquals(1, result.keptHdr);
        assertTrue("the set keeping a rendition stays", result.manifest.contains("<AdaptationSet id=\"1\""));
    }

    @Test
    public void aVideoServedOnlyInHdrIsLeftWhole() {
        String manifest = mpd(videoSet(0, 18, rendition("h720", "vp09.02.31.10", 720), rendition("h1080", "vp09.02.40.10", 1080)), AUDIO_SET);
        assertSame(manifest, SdrRenditions.manifest(manifest));
        SdrRenditions.Result result = SdrRenditions.filter(manifest);
        assertEquals(0, result.skipped);
        assertEquals(2, result.keptHdr);
    }

    @Test
    public void aManifestWithNoHdrComesBackAsItWas() throws Exception {
        String fixture = new String(Files.readAllBytes(Paths.get(getClass()
                .getResource("/app/morphe/extension/facebook/download/meta-public-dash.mpd").toURI())), StandardCharsets.UTF_8);
        assertSame(fixture, SdrRenditions.manifest(fixture));
    }

    @Test
    public void theCodecStringAloneMarksHdrInAMixedSet() {
        // One set, no transfer property: the full codec strings say which rendition is HLG or PQ.
        String manifest = mpd(videoSet(0, 0,
                rendition("s1080", "vp09.00.40.08.00.01.01.01.00", 1080),
                rendition("hlg1080", "vp09.02.40.10.01.09.18.09.00", 1080),
                rendition("pq720", "av01.0.05M.10.0.112.09.16.09.0", 720),
                rendition("dv1080", "dvh1.08.06", 1080),
                rendition("ten-bit-sdr", "vp09.02.40.10.01.01.01.01.00", 1080)));
        SdrRenditions.Result result = SdrRenditions.filter(manifest);
        assertEquals(Arrays.asList("s1080", "ten-bit-sdr"), ids(result.manifest));
        assertEquals(3, result.skipped);
    }

    @Test
    public void aRenditionsOwnTransferWinsOverItsSets() {
        String sdrInHdrSet = "<Representation id=\"s1080\" codecs=\"hvc1.1.6.L120.90\" mimeType=\"video/mp4\" height=\"1080\">"
                + transfer(1) + "</Representation>";
        String manifest = mpd(videoSet(0, 16, sdrInHdrSet, rendition("h1080", "hvc1.2.4.L120.90", 1080)));
        assertEquals(Arrays.asList("s1080"), ids(SdrRenditions.filter(manifest).manifest));
    }

    @Test
    public void anHdrRenditionOfUnknownHeightStays() {
        String manifest = mpd(videoSet(0, 1, rendition("s1080", "avc1.64002a", 1080)), videoSet(1, 18, rendition("h", "vp09.02.40.10", 0)));
        SdrRenditions.Result result = SdrRenditions.filter(manifest);
        assertEquals(Arrays.asList("s1080", "h"), ids(result.manifest));
        assertEquals(0, result.skipped);
    }

    @Test
    public void offOrPausedTheManifestGoesThroughAsItCame() {
        String manifest = mpd(videoSet(0, 1, rendition("s1080", "avc1.64002a", 1080)), videoSet(1, 18, rendition("h1080", "vp09.02.40.10", 1080)));
        assertEquals("the control: on, the HDR set goes", Arrays.asList("s1080"), ids(SdrRenditions.manifest(manifest)));
        for (HushfacebookPause.Reason reason : PAUSES) {
            PauseForTests.pause(reason);
            assertSame(reason.name(), manifest, SdrRenditions.manifest(manifest));
            PauseForTests.resume();
        }
        Settings.PREFER_SDR_RENDITIONS.save(false);
        assertSame(manifest, SdrRenditions.manifest(manifest));
    }

    @Test
    public void aBrokenManifestIsHandedBackUntouched() {
        assertSame(null, SdrRenditions.manifest(null));
        String broken = "<MPD><Period><AdaptationSet contentType=\"video\">" + transfer(18) + "<Representation id=\"x\" height=\"720\"";
        assertSame(broken, SdrRenditions.manifest(broken));
    }

    @Test
    public void codecStrings() {
        assertTrue(SdrRenditions.hdrCodecs("vp09.02.10.10.01.09.16.09.01"));
        assertTrue(SdrRenditions.hdrCodecs("av01.0.04M.10.0.112.09.18.09.0"));
        assertTrue(SdrRenditions.hdrCodecs("dvhe.05.06"));
        assertTrue(SdrRenditions.hdrCodecs("mp4a.40.2, dav1.10.06"));
        assertFalse(SdrRenditions.hdrCodecs("vp09.02.10.10"));
        assertFalse(SdrRenditions.hdrCodecs("av01.0.04M.08.0.111.01.01.01.0"));
        assertFalse(SdrRenditions.hdrCodecs("avc1.64001f"));
        assertFalse(SdrRenditions.hdrCodecs("vp09.00.xx.08.00.01.zz.01.00"));
        assertFalse(SdrRenditions.hdrCodecs(null));
    }
}
