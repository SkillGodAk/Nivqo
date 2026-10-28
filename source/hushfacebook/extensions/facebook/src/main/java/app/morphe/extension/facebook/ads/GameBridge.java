/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The bridge Hushfacebook puts in front of an Instant Game's own (#168).
 *
 * <p>Most games now run inside Facebook's main screen, from code Facebook downloads after install,
 * and that code brings its own copy of the bridge the game's SDK talks to. The patch can't reach
 * it, so the Block Instant Games ads patch gives the WebView every game runs in an
 * {@code addJavascriptInterface} that hands each bridge to {@link #wrap} first. A bridge registered
 * under the game's name, whose one JavaScript method is {@code postMessage(String, String)}, comes
 * back inside this class: each message goes to {@link GameAds#heldPromise}, an ad message is
 * answered here the way Facebook answers one, and every other message goes on to the game's own
 * bridge untouched. Anything else is registered as it came.
 */
public final class GameBridge {
    /** The name the game's SDK finds Facebook's bridge under. */
    static final String NAME = "QuicksilverAndroid";

    /** How Facebook hands the game an answer, with the answer's JSON in place of the %s. */
    static final String DISPATCH = "e = new Event('message');e.data = %s;window.dispatchEvent(e);";

    /** The HookStatus count of game bridges put behind this one. */
    static final String WRAPPED = "game bridges wrapped";

    /** The HookStatus count of game bridges left alone because they offer more than postMessage. */
    static final String LEFT_ALONE = "game bridges left alone (more methods than postMessage)";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final WebView view;
    private final Object target;
    private final Method postMessage;

    private GameBridge(WebView view, Object target, Method postMessage) {
        this.view = view;
        this.target = target;
        this.postMessage = postMessage;
    }

    /**
     * Injection point, the game WebView's {@code addJavascriptInterface}. Answers what to register
     * under [name]: this bridge around [target] for a game bridge, otherwise [target] itself.
     * Never throws.
     */
    public static Object wrap(@Nullable WebView view, @Nullable Object target, @Nullable String name) {
        try {
            if (view == null || target == null || target instanceof GameBridge || !NAME.equals(name)) {
                return target;
            }
            HookStatus.invoked(FamilyNames.GAME_ADS);
            Method method = onlyPostMessage(target.getClass());
            if (method == null) {
                HookStatus.counted(FamilyNames.GAME_ADS, LEFT_ALONE);
                Logger.printDebug(() -> "Instant Games ads: left " + target.getClass().getName() + " alone");
                return target;
            }
            method.setAccessible(true);
            HookStatus.counted(FamilyNames.GAME_ADS, WRAPPED);
            Logger.printDebug(() -> "Instant Games ads: wrapped " + target.getClass().getName());
            return new GameBridge(view, target, method);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GAME_ADS, "game bridge", failure);
            return target;
        }
    }

    /**
     * The game's one JavaScript method, {@code postMessage(String, String)}, or null when the game
     * bridge offers any other, which this class couldn't pass on.
     */
    @Nullable
    private static Method onlyPostMessage(Class<?> type) {
        Method found = null;
        for (Method method : type.getMethods()) {
            if (!method.isAnnotationPresent(JavascriptInterface.class)) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (found != null || !"postMessage".equals(method.getName()) || parameters.length != 2
                    || parameters[0] != String.class || parameters[1] != String.class) {
                return null;
            }
            found = method;
        }
        return found;
    }

    /** What the game's SDK calls. An ad message is answered here, anything else goes on. */
    @JavascriptInterface
    public void postMessage(String message, String origin) throws Throwable {
        String promise = GameAds.heldPromise(message);
        String answer = promise != null ? answer(promise, message) : null;
        if (answer == null) {
            forward(message, origin);
            return;
        }
        MAIN.post(() -> {
            try {
                view.evaluateJavascript(answer, null);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.GAME_ADS, "game bridge answer", failure);
            }
        });
    }

    /**
     * The script that settles [promise] for the ad [message]: resolved with what
     * {@link GameAds#answer} gives, or rejected with the SDK's code. Null if it can't be built,
     * and then the message goes on to Facebook rather than leave the game waiting.
     */
    @Nullable
    static String answer(String promise, String message) {
        try {
            Object data = GameAds.answer(message);
            JSONObject content = new JSONObject().put("promiseID", promise);
            String type;
            if (data != null) {
                type = "resolvepromise";
                content.put("data", data);
            } else {
                type = "rejectpromise";
                content.put("data", new JSONObject()
                        .put("code", GameAds.rejection(message))
                        .put("message", GameAds.NO_AD));
            }
            JSONObject event = new JSONObject()
                    .put("type", type)
                    .put("content", content)
                    .put("source", "android");
            return String.format(Locale.ROOT, DISPATCH, event);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.GAME_ADS, "game bridge answer", failure);
            return null;
        }
    }

    /** Hands [message] to the game's own bridge, as if this one weren't there. */
    private void forward(String message, String origin) throws Throwable {
        GameAds.forwarding(true);
        try {
            postMessage.invoke(target, message, origin);
        } catch (InvocationTargetException thrown) {
            throw thrown.getCause() != null ? thrown.getCause() : thrown;
        } finally {
            GameAds.forwarding(false);
        }
    }
}
