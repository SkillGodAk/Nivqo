/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;
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

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The bridge Hushfacebook puts in front of a game's own (#168): only a game bridge is wrapped, an
 * ad message is answered on the page the way Facebook answers one, and everything else reaches the
 * game's bridge as it came.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class GameBridgeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private WebView view;

    /** A game bridge like the one Facebook's downloaded game code registers. */
    public static final class FacebookBridge {
        final List<String> got = new ArrayList<>();
        final List<String> asked = new ArrayList<>();

        @JavascriptInterface
        public void postMessage(String message, String origin) {
            got.add(message);
            // What the hooked bridge in the main dex asks: nothing, once GameBridge asked already.
            asked.add(String.valueOf(GameAds.heldPromise(message)));
        }
    }

    /** A bridge with a second JavaScript method, which a wrapper couldn't pass on. */
    public static final class WiderBridge {
        @JavascriptInterface
        public void postMessage(String message, String origin) {
        }

        @JavascriptInterface
        public String getContext() {
            return "";
        }
    }

    public static final class ThrowingBridge {
        @JavascriptInterface
        public void postMessage(String message, String origin) {
            throw new IllegalStateException("the game's own failure");
        }
    }

    @Before
    public void view() {
        view = new WebView(RuntimeEnvironment.getApplication());
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.BLOCK_GAME_ADS.resetToDefault();
        Settings.ANSWER_REWARDED_GAME_ADS.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String message(String type, String promise) {
        return "{\"type\":\"" + type + "\",\"content\":{\"promiseID\":\"" + promise + "\",\"placementID\":\"1_2\"}}";
    }

    /** The event the page was handed last, as the JSON between "e.data = " and the dispatch. */
    private JSONObject lastEvent() throws Exception {
        shadowOf(Looper.getMainLooper()).idle();
        String script = shadowOf(view).getLastEvaluatedJavascript();
        String start = "e = new Event('message');e.data = ";
        String end = ";window.dispatchEvent(e);";
        assertTrue(String.valueOf(script), script != null && script.startsWith(start) && script.endsWith(end));
        return new JSONObject(script.substring(start.length(), script.length() - end.length()));
    }

    @Test
    public void onlyAGameBridgeIsWrapped() {
        FacebookBridge bridge = new FacebookBridge();
        assertSame("another name", bridge, GameBridge.wrap(view, bridge, "Android"));
        assertSame("no view", bridge, GameBridge.wrap(null, bridge, GameBridge.NAME));
        assertNull(GameBridge.wrap(view, null, GameBridge.NAME));
        WiderBridge wider = new WiderBridge();
        assertSame("more methods than postMessage", wider, GameBridge.wrap(view, wider, GameBridge.NAME));
        Object wrapped = GameBridge.wrap(view, bridge, GameBridge.NAME);
        assertTrue(String.valueOf(wrapped), wrapped instanceof GameBridge);
        assertSame("wrapped once", wrapped, GameBridge.wrap(view, wrapped, GameBridge.NAME));
        String report = HookStatus.report("").toString();
        assertTrue(report, report.contains(GameBridge.WRAPPED + " 1"));
        assertTrue(report, report.contains(GameBridge.LEFT_ALONE + " 1"));
    }

    @Test
    public void anAdIsRejectedOnThePageAndNeverReachesFacebook() throws Throwable {
        FacebookBridge bridge = new FacebookBridge();
        GameBridge wrapped = (GameBridge) GameBridge.wrap(view, bridge, GameBridge.NAME);
        wrapped.postMessage(message("loadadasync", "7"), "https://apps.fbsbx.com");
        assertEquals(0, bridge.got.size());
        JSONObject event = lastEvent();
        assertEquals("rejectpromise", event.getString("type"));
        assertEquals("android", event.getString("source"));
        JSONObject content = event.getJSONObject("content");
        assertEquals("7", content.getString("promiseID"));
        assertEquals("ADS_NO_FILL", content.getJSONObject("data").getString("code"));
        assertEquals(GameAds.NO_AD, content.getJSONObject("data").getString("message"));
    }

    @Test
    public void aRewardedVideoResolvesAsWatchedWithTheSwitchOn() throws Throwable {
        Settings.ANSWER_REWARDED_GAME_ADS.save(true);
        GameBridge wrapped = (GameBridge) GameBridge.wrap(view, new FacebookBridge(), GameBridge.NAME);
        wrapped.postMessage(message("getrewardedvideoasync", "8"), "");
        JSONObject event = lastEvent();
        assertEquals("resolvepromise", event.getString("type"));
        JSONObject content = event.getJSONObject("content");
        assertEquals("8", content.getString("promiseID"));
        assertTrue(content.toString(), content.getJSONObject("data").getString("adInstanceID").startsWith(GameAds.WATCHED));
    }

    /** Other messages, and ads with the switch off, reach the game's bridge, which isn't asked twice. */
    @Test
    public void everythingElseGoesOnAsItCame() throws Throwable {
        FacebookBridge bridge = new FacebookBridge();
        GameBridge wrapped = (GameBridge) GameBridge.wrap(view, bridge, GameBridge.NAME);
        String other = message("setplayerdataasync", "1");
        wrapped.postMessage(other, "");
        Settings.BLOCK_GAME_ADS.save(false);
        String ad = message("loadadasync", "2");
        wrapped.postMessage(ad, "");
        assertEquals(List.of(other, ad), bridge.got);
        assertEquals(List.of("null", "null"), bridge.asked);
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(view).getLastEvaluatedJavascript());
        String report = HookStatus.report("").toString();
        assertTrue(report, report.contains(FamilyNames.GAME_ADS + ": invoked 3"));
        // Asked again outside a forward, the hooked bridge answers as before.
        Settings.BLOCK_GAME_ADS.resetToDefault();
        assertEquals("3", GameAds.heldPromise(message("loadadasync", "3")));
    }

    @Test
    public void theGameBridgesOwnFailureIsItsOwn() throws Throwable {
        GameBridge wrapped = (GameBridge) GameBridge.wrap(view, new ThrowingBridge(), GameBridge.NAME);
        try {
            wrapped.postMessage(message("setplayerdataasync", "1"), "");
            throw new AssertionError("the failure was swallowed");
        } catch (IllegalStateException expected) {
            assertEquals("the game's own failure", expected.getMessage());
        }
        assertEquals("a forward that threw still clears the guard", "4",
                GameAds.heldPromise(message("loadadasync", "4")));
    }
}
