/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.notifications;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/** Observed outcomes, never tokens, payloads, account identifiers or exception messages. */
@SuppressWarnings("unused")
public final class PushDiagnostics {
    enum Status { NOT_OBSERVED, ACCEPTED, REJECTED, FAILED, CONNECTING, CONNECTED, CLOSED, RECEIVED, UNKNOWN }
    enum Route {
        FCM("FCM server registration", 0), GCM("GCM server registration", 0),
        FBNS("FBNS server registration", 0), FBNS_LITE("FBNS Lite server registration", 0),
        MQTT("FBNS MQTT transport", 1),
        MESSAGES("FBNS gateway messages", 2), HIGH_PRIORITY("FBNS gateway priority messages", 2),
        REGISTRATION("FBNS gateway registration stream", 2), ACKS("FBNS gateway acknowledgments", 2),
        FCM_RECEIPT("FCM receive callback", 3);

        final String label;
        final int observer;
        Route(String label, int observer) { this.label = label; this.observer = observer; }
        boolean allows(Status value) {
            return value == Status.NOT_OBSERVED || value == Status.UNKNOWN || (observer == 0
                    ? value == Status.ACCEPTED || value == Status.REJECTED || value == Status.FAILED
                    : observer == 3 ? value == Status.RECEIVED
                    : value == Status.CONNECTING || value == Status.CONNECTED || value == Status.CLOSED);
        }
    }

    private static final Object LOCK = new Object();
    private static final Window window = new Window();
    private static PushStateStore store;
    private static boolean workerQueued, initializePending;

    /** A live process also starts over on a clock discontinuity, discarding its previous batch. */
    static final class Window {
        PushStateStore.State state;
        void start(long elapsed, long wall) {
            if (state == null) state = new PushStateStore.State(elapsed, wall);
            else if (!state.sameClock(elapsed, wall)) {
                state = new PushStateStore.State(elapsed, wall);
                state.historyLost = true;
            }
        }
        void observe(Route route, Status status, long elapsed, long wall) {
            start(elapsed, wall);
            boolean positive = status == Status.ACCEPTED || status == Status.CONNECTED || status == Status.RECEIVED;
            state.rows[route.ordinal()].add(status, elapsed, 1, positive ? elapsed : -1);
        }
        PushStateStore.State take() {
            PushStateStore.State batch = state;
            state = new PushStateStore.State(batch.startElapsed, batch.startWall);
            return batch;
        }
        void restore(PushStateStore.State batch) {
            if (!batch.sameOrigin(state.startElapsed, state.startWall)) return;
            state.historyLost |= batch.historyLost;
            for (int i = 0; i < state.rows.length; i++) state.rows[i].merge(batch.rows[i]);
        }
    }

    /** Flipped only after the patch installs each complete group of observers. */
    public static boolean hasRegistrationObserver() { return false; }
    public static boolean hasMqttObserver() { return false; }
    public static boolean hasGatewayObserver() { return false; }
    public static boolean hasFcmReceiptObserver() { return false; }

    public static final LogBufferManager.ReportSection REPORT = new LogBufferManager.ReportSection() {
        @Override public String title() { return "PUSH"; }
        @Override public boolean isAppState() { return true; }
        @Override public List<String> lines() {
            PushStateStore.State state = null;
            boolean waiting;
            synchronized (LOCK) {
                window.start(SystemClock.elapsedRealtime(), System.currentTimeMillis());
                schedule();
                waiting = workerQueued || initializePending || hasPending();
                try {
                    if (store != null) state = store.read();
                } catch (IOException | RuntimeException unreadable) { /* Report unknown, never infer an absent callback. */ }
            }
            return report(state, SystemClock.elapsedRealtime(), new boolean[] {
                    hasRegistrationObserver(), hasMqttObserver(), hasGatewayObserver(), hasFcmReceiptObserver()
            }, waiting);
        }
    };

    private PushDiagnostics() {}

    /** Before the main-process check, so :notification records can be read from the main report. */
    public static void initialize(Context context) {
        if (!hasRegistrationObserver() && !hasMqttObserver() && !hasGatewayObserver() && !hasFcmReceiptObserver()) return;
        try {
            synchronized (LOCK) {
                window.start(SystemClock.elapsedRealtime(), System.currentTimeMillis());
                if (store == null) {
                    int boot = -1;
                    try { boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
                    catch (RuntimeException unavailable) { /* The report keeps the elapsed-clock limitation explicit. */ }
                    store = new PushStateStore(context.getNoBackupFilesDir(), boot);
                    initializePending = true;
                }
            }
            schedule();
        } catch (Throwable unreadable) { /* The report will say records unavailable. */ }
    }

    /** The Boolean is Facebook's validated RegisterPushTokenResult, not a request or callback name. */
    public static void registrationResult(Enum<?> route, boolean accepted) {
        record(registrationRoute(route), accepted ? Status.ACCEPTED : Status.REJECTED);
    }

    public static void registrationFailed(Enum<?> route) { record(registrationRoute(route), Status.FAILED); }
    public static void fcmCallback() { record(Route.FCM_RECEIPT, Status.RECEIVED); }

    public static void mqttState(Object delegate, Enum<?> state) {
        try {
            if (delegate == null || state == null) return;
            for (Class<?> type = delegate.getClass(); type != null; type = type.getSuperclass()) {
                if ("com.facebook.rti.push.service.FbnsServiceDelegate".equals(type.getName())) {
                    String name = state.name();
                    record(Route.MQTT, "CONNECTED".equals(name) ? Status.CONNECTED
                            : "CONNECTING".equals(name) ? Status.CONNECTING
                            : "DISCONNECTED".equals(name) ? Status.CLOSED : Status.UNKNOWN);
                    return;
                }
            }
        } catch (Throwable unreadable) { /* Observation must never alter Facebook's state machine. */ }
    }

    public static void gatewayReady(Object handler) { record(gatewayRoute(handler), Status.CONNECTED); }
    public static void gatewayClosed(Object handler) { record(gatewayRoute(handler), Status.CLOSED); }

    static Route registrationRoute(Enum<?> value) {
        if (value == null) return null;
        switch (value.name()) {
            case "FCM": return Route.FCM;
            case "GCM": case "GCM_V3": return Route.GCM;
            case "FBNS": return Route.FBNS;
            case "FBNS_LITE": return Route.FBNS_LITE;
            default: return null;
        }
    }

    /** Only this kept getter is read. An unknown name is discarded without retaining it. */
    static Route gatewayRoute(Object handler) {
        if (handler == null) return null;
        try {
            Class<?> type = handler.getClass();
            while (type != null && !"com.facebook.rti.notifgateway.streamhandler.NotifGatewayStreamHandler".equals(type.getName())) {
                type = type.getSuperclass();
            }
            if (type == null) return null;
            Object value = type.getMethod("getStreamName").invoke(handler);
            if ("/fbns_msg".equals(value)) return Route.MESSAGES;
            if ("/fbns_msg_hp".equals(value)) return Route.HIGH_PRIORITY;
            if ("/fbns_reg_req".equals(value)) return Route.REGISTRATION;
            if ("/fbns_msg_ack".equals(value)) return Route.ACKS;
        } catch (Throwable unreadable) { /* No exception message or object is retained. */ }
        return null;
    }

    private static void record(Route route, Status status) {
        if (route == null) return;
        try {
            synchronized (LOCK) {
                window.observe(route, status, SystemClock.elapsedRealtime(), System.currentTimeMillis());
            }
            schedule();
        } catch (Throwable unreadable) { /* A diagnostic is never a push-processing failure. */ }
    }

    private static boolean hasPending() {
        if (window.state != null) for (PushStateStore.Row row : window.state.rows) if (row.count != 0) return true;
        return false;
    }

    private static void schedule() {
        synchronized (LOCK) {
            if (workerQueued || store == null || (!initializePending && !hasPending()
                    && (window.state == null || !window.state.historyLost))) return;
            workerQueued = true;
            if (!Utils.runOnBackgroundThread(PushDiagnostics::flush)) workerQueued = false;
        }
    }

    private static void flush() {
        boolean written = false;
        PushStateStore.State batch;
        synchronized (LOCK) {
            window.start(SystemClock.elapsedRealtime(), System.currentTimeMillis());
            batch = window.take();
        }
        try {
            store.merge(batch);
            written = true;
        } catch (IOException | RuntimeException unreadable) {
            synchronized (LOCK) {
                window.restore(batch);
            }
        } finally {
            synchronized (LOCK) {
                if (written) initializePending = false;
                workerQueued = false;
            }
            if (written) schedule();
        }
    }

    static List<String> report(PushStateStore.State state, long now, boolean[] available, boolean waiting) {
        List<String> out = new ArrayList<>();
        out.add(state == null ? "observation window: unknown (private records unavailable)"
                : "observation window: began " + seconds(now - state.startElapsed) + " s before this report"
                    + (state.boot >= 0 ? ", in this Android boot" : "; boot counter unavailable, using the elapsed clock"));
        out.add("Times count from this observation window, which survives Facebook process restarts. They do not measure sign-in or delivery.");
        if (state != null && state.historyLost) out.add("Earlier observations are unavailable. An empty route stays unknown until observed again.");
        if (waiting) out.add("Private record updates are pending; the latest callback may not be included yet.");
        for (Route route : Route.values()) {
            PushStateStore.Row row = state == null ? null : state.rows[route.ordinal()];
            String value;
            if (!available[route.observer]) value = "unknown (observer unavailable in this build)";
            else if (row == null || (row.count == 0 && state.historyLost)) value = "unknown (records unavailable)";
            else if (row.count == 0) value = "not observed in this window";
            else {
                value = row.status.name().toLowerCase(Locale.ROOT);
                if (row.status == Status.CONNECTED && route.observer == 2) value = "stream ready";
                value += "; first +" + seconds(row.first - state.startElapsed) + " s, latest +"
                        + seconds(row.last - state.startElapsed) + " s; observations " + row.count
                        + (row.count == PushStateStore.MAX_COUNT ? "+" : "");
                if (row.firstSuccess >= 0) value += "; first positive outcome +" + seconds(row.firstSuccess - state.startElapsed) + " s";
            }
            out.add(route.label + ": " + value);
        }
        out.add("An accepted server result, a ready transport and a receive callback are separate observations. Notification display is unverified.");
        return out;
    }

    private static long seconds(long milliseconds) { return Math.max(0, milliseconds / 1000); }
}
