/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.font;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.LineHeightSpan;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Issue #96: with `Use the system font` on, some phones' fonts lost the tails of y, g and p in
 * Facebook, because they declare far less descent than their letters use and Facebook lays its text
 * out without font padding. The padding goes on for those fonts only, while the switch is on, and
 * only for the phone's font or the picked file.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class OwnFontPaddingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    @After
    public void clean() {
        OwnFont.OVERFLOWS.clear();
        HookStatus.clear();
        Settings.USE_SYSTEM_FONT.resetToDefault();
    }

    private static TextPaint paint(Typeface face) {
        TextPaint paint = new TextPaint();
        paint.setTypeface(face);
        return paint;
    }

    /** Measured at 100 px. Roboto keeps its letters inside; the reporter's font draws 20 px below its descent. */
    @Test
    public void lettersPastTheLineBoxByMoreThanTwoPercentOverflow() {
        assertFalse("Roboto", OwnFont.reachesPast(-92.8f, 24.4f, -73, 21));
        assertFalse("MiSans", OwnFont.reachesPast(-104.4f, 28.2f, -74, 23));
        assertFalse("within the slack", OwnFont.reachesPast(-90f, 20f, -91, 22));
        assertTrue("tails below the descent", OwnFont.reachesPast(-95f, 2f, -74, 22));
        assertTrue("ascenders above the ascent", OwnFont.reachesPast(-60f, 25f, -75, 21));
    }

    private static Paint.FontMetricsInt box(int ascent, int descent) {
        Paint.FontMetricsInt metrics = new Paint.FontMetricsInt();
        metrics.ascent = ascent;
        metrics.descent = descent;
        return metrics;
    }

    /**
     * The reporter's Bengali captions lost their vowel signs while Latin fit. A font that draws a
     * script itself past the line a fallback-spaced layout gives it pads. One whose script comes
     * from the phone's fallback font, drawn as a serif paint draws it, keeps Facebook's layout, as
     * does a line the fallback's taller metrics already hold, and a script no font draws.
     */
    @Test
    public void aScriptsVowelSignsPastTheLineBoxOverflow() {
        Paint.FontMetricsInt own = box(-93, 21);
        Rect stock = new Rect(0, -96, 640, 35);
        assertTrue("signs below the descent", OwnFont.scriptReachesPast(own, own, new Rect(0, -80, 600, 33), stock));
        assertTrue("signs above the ascent", OwnFont.scriptReachesPast(own, own, new Rect(0, -101, 600, 20), stock));
        assertFalse("inside the slack", OwnFont.scriptReachesPast(own, own, new Rect(0, -94, 600, 22), stock));
        assertFalse("the phone's fallback draws it", OwnFont.scriptReachesPast(own, box(-90, 24), new Rect(stock), stock));
        assertFalse("the fallback's own descent holds it",
                OwnFont.scriptReachesPast(own, box(-97, 36), new Rect(0, -80, 600, 33), stock));
        assertFalse("no font draws it", OwnFont.scriptReachesPast(own, own, new Rect(), new Rect()));
        for (String script : OwnFont.SCRIPTS) {
            Character.UnicodeScript first = Character.UnicodeScript.of(script.codePointAt(0));
            script.codePoints().forEach(c -> assertEquals("one script to a probe", first, Character.UnicodeScript.of(c)));
        }
    }

    @Test
    public void aFontThatOverflowsIsPaddedOnlyWhileTheSwitchIsOn() {
        OwnFont.OVERFLOWS.put(Typeface.DEFAULT, true);
        Settings.USE_SYSTEM_FONT.save(false);
        assertFalse("switch off", OwnFont.needsPad(paint(Typeface.DEFAULT)));

        Settings.USE_SYSTEM_FONT.save(true);
        assertTrue("the phone's font", OwnFont.needsPad(paint(Typeface.DEFAULT)));
        assertTrue("no typeface draws in the phone's", OwnFont.needsPad(paint(null)));
        assertFalse("no paint", OwnFont.needsPad(null));
    }

    @Test
    public void aFontThatFitsOrIsntThePhonesKeepsFacebooksLayout() {
        Settings.USE_SYSTEM_FONT.save(true);
        OwnFont.OVERFLOWS.put(Typeface.DEFAULT, false);
        assertFalse("fits", OwnFont.needsPad(paint(Typeface.DEFAULT)));

        OwnFont.OVERFLOWS.put(Typeface.SERIF, true);
        assertFalse("a font of the story's or Meta's", OwnFont.needsPad(paint(Typeface.SERIF)));
    }

    /** The builder obtained last on this thread carries its paint's answer to the padding choice, and only that builder. */
    @Test
    public void theBuilderObtainedLastIsPaddedForItsPaint() {
        Settings.USE_SYSTEM_FONT.save(true);
        OwnFont.OVERFLOWS.put(Typeface.DEFAULT, true);
        StaticLayout.Builder other = OwnFont.obtain("Playback speed", 0, 14, paint(Typeface.SERIF), 400);
        assertFalse("a font that isn't the phone's", OwnFont.padsFor(other));

        StaticLayout.Builder builder = OwnFont.obtain("Playback speed", 0, 14, paint(Typeface.DEFAULT), 400);
        assertTrue(OwnFont.padsFor(builder));
        assertFalse("a builder obtained before", OwnFont.padsFor(other));
        assertSame("the same builder comes back", builder, OwnFont.setIncludePad(builder, false));

        Settings.USE_SYSTEM_FONT.save(false);
        assertFalse("switch off", OwnFont.padsFor(builder));
    }

    @Test
    public void aTextViewWithoutPaddingTakesItForAnOverflowingFont() {
        TextView view = new TextView(RuntimeEnvironment.getApplication());
        view.setTypeface(Typeface.DEFAULT);
        view.setIncludeFontPadding(false);
        OwnFont.OVERFLOWS.put(Typeface.DEFAULT, true);

        Settings.USE_SYSTEM_FONT.save(false);
        OwnFont.textView(view);
        assertFalse("switch off", view.getIncludeFontPadding());

        Settings.USE_SYSTEM_FONT.save(true);
        OwnFont.textView(view);
        assertTrue(view.getIncludeFontPadding());
    }

    /**
     * A line-height span of Facebook's, as the patch leaves it: it moves each line's top and bottom
     * [inward] px inside the font's own through the stand-ins, and keeps the font's edges and what
     * each line ended with.
     */
    private static final class LineHeight implements LineHeightSpan {
        final int inward;
        final List<int[]> lines = new ArrayList<>();

        LineHeight(int inward) {
            this.inward = inward;
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int lineHeight, Paint.FontMetricsInt line) {
            int top = line.top;
            int bottom = line.bottom;
            OwnFont.lineTop(top + inward, line);
            OwnFont.lineBottom(bottom - inward, line);
            lines.add(new int[] {top, bottom, line.top, line.bottom});
        }
    }

    /** Lays "gjpqy" out in [face] the way Facebook's text does, through the stand-ins, with [span] over it. */
    private static List<int[]> laidOut(Typeface face, LineHeight span) {
        SpannableString text = new SpannableString("gjpqy");
        text.setSpan(span, 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        StaticLayout.Builder builder = OwnFont.obtain(text, 0, text.length(), paint(face), 1000);
        assertNotNull(OwnFont.build(OwnFont.setIncludePad(builder, false)));
        assertFalse("the span chose no line's height", span.lines.isEmpty());
        return span.lines;
    }

    /** Whether every line ended with the font's own edges (true) or the span's (false). */
    private static void assertEdges(String what, boolean fontsOwn, int inward, List<int[]> lines) {
        for (int[] line : lines) {
            int top = fontsOwn ? line[0] : line[0] + inward;
            int bottom = fontsOwn ? line[1] : line[1] - inward;
            assertEquals(what + ": top", top, line[2]);
            assertEquals(what + ": bottom", bottom, line[3]);
        }
    }

    /**
     * Facebook's line-height spans set a layout's first top and last bottom to the edges of the line
     * height they fit, which took back the padding of a font drawing past its line box (#96). In a
     * layout built for such a font with the switch on, the edges stay at least the font's own; a
     * font that fits, the switch off, and anything outside such a layout keep Facebook's edges.
     */
    @Test
    public void aLineHeightSpanLeavesAPaddedLayoutsEdgesAtTheFontsOwn() {
        Settings.USE_SYSTEM_FONT.save(true);
        OwnFont.OVERFLOWS.put(Typeface.DEFAULT, true);
        assertTrue("nothing kept yet", HookStatus.report().isEmpty());
        List<int[]> padded = laidOut(Typeface.DEFAULT, new LineHeight(7));
        assertEdges("padded", true, 7, padded);
        List<String> report = HookStatus.report();
        assertEquals(report.toString(), 1, report.size());
        assertTrue(report.get(0), report.get(0).contains(OwnFont.EDGE_KEPT + " " + (2 * padded.size())));
        assertEdges("an edge already past the font's stays Facebook's", false, -3, laidOut(Typeface.DEFAULT, new LineHeight(-3)));
        assertEquals("only an edge pulled inside is counted", report, HookStatus.report());

        Paint.FontMetricsInt line = new Paint.FontMetricsInt();
        line.top = -110;
        line.bottom = 30;
        OwnFont.lineTop(-80, line);
        OwnFont.lineBottom(5, line);
        assertEquals("outside a layout, once the padded one is built", -80, line.top);
        assertEquals("outside a layout, once the padded one is built", 5, line.bottom);

        assertEdges("a font that isn't the phone's", false, 7, laidOut(Typeface.SERIF, new LineHeight(7)));
        OwnFont.OVERFLOWS.put(Typeface.DEFAULT, false);
        assertEdges("a font that fits", false, 7, laidOut(Typeface.DEFAULT, new LineHeight(7)));
        OwnFont.OVERFLOWS.put(Typeface.DEFAULT, true);
        Settings.USE_SYSTEM_FONT.save(false);
        assertEdges("switch off", false, 7, laidOut(Typeface.DEFAULT, new LineHeight(7)));
    }

    /** Each typeface is measured once, and the report counts what the measure found. */
    @Test
    public void eachTypefaceIsMeasuredOnceAndCounted() {
        boolean first = OwnFont.overflows(Typeface.MONOSPACE);
        assertEquals(first, OwnFont.overflows(Typeface.MONOSPACE));
        List<String> lines = HookStatus.report();
        assertEquals(lines.toString(), 1, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains((first ? OwnFont.PADDED : OwnFont.FITS) + " 1"));
    }
}
