/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.notifications;

import com.facebook.rti.notifgateway.streamhandler.NotifGatewayStreamHandler;
import com.facebook.rti.push.service.FbnsServiceDelegate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.List;

import static app.morphe.extension.facebook.notifications.PushDiagnostics.Route;
import static app.morphe.extension.facebook.notifications.PushDiagnostics.Status;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PushDiagnosticsTest {
    private static final long EPOCH = 1_800_000_000_000L;
    private PushDiagnostics.Window window;

    // Enum.name() is safe even when toString() contains sensitive text or throws.
    private enum PushRoute {
        FCM, GCM, GCM_V3, FBNS, FBNS_LITE, ADM, UNKNOWN;
        @Override public String toString() { throw new AssertionError("token-must-not-be-read"); }
    }
    private enum MqttState {
        CONNECTING, CONNECTED, DISCONNECTED, ERROR;
        @Override public String toString() { throw new AssertionError("error-must-not-be-read"); }
    }
    private static final class Handler extends NotifGatewayStreamHandler {
        final String route;
        Handler(String route) { this.route = route; }
        @Override public String getStreamName() { return route; }
        @Override public String toString() { throw new AssertionError("payload-must-not-be-read"); }
    }

    @Before public void reset() throws Exception {
        Field field = PushDiagnostics.class.getDeclaredField("window");
        field.setAccessible(true);
        window = (PushDiagnostics.Window) field.get(null);
        window.state = null;
    }
    @After public void clear() { window.state = null; }

    @Test public void successfulCallbackCanStillContainARejectedServerResult() {
        PushDiagnostics.registrationResult(PushRoute.FCM, false);
        assertEquals(Status.REJECTED, window.state.rows[Route.FCM.ordinal()].status);
        assertEquals(-1, window.state.rows[Route.FCM.ordinal()].firstSuccess);
        PushDiagnostics.registrationResult(PushRoute.FCM, true);
        assertEquals(Status.ACCEPTED, window.state.rows[Route.FCM.ordinal()].status);
        assertTrue(window.state.rows[Route.FCM.ordinal()].firstSuccess >= 0);
        PushDiagnostics.registrationFailed(PushRoute.FCM);
        assertEquals(Status.FAILED, window.state.rows[Route.FCM.ordinal()].status);
        assertEquals(3, window.state.rows[Route.FCM.ordinal()].count);
    }

    @Test public void onlyKnownRoutesAndTheFbnsDelegateAreCollected() {
        assertEquals(Route.GCM, PushDiagnostics.registrationRoute(PushRoute.GCM_V3));
        assertNull(PushDiagnostics.registrationRoute(PushRoute.ADM));
        assertNull(PushDiagnostics.registrationRoute(null));
        PushDiagnostics.registrationResult(PushRoute.UNKNOWN, true);
        PushDiagnostics.mqttState(new Object(), MqttState.CONNECTED);
        assertNull(window.state);
        PushDiagnostics.mqttState(new FbnsServiceDelegate() {}, MqttState.CONNECTING);
        PushDiagnostics.mqttState(new FbnsServiceDelegate(), MqttState.CONNECTED);
        PushDiagnostics.mqttState(new FbnsServiceDelegate(), MqttState.DISCONNECTED);
        assertEquals(Status.CLOSED, window.state.rows[Route.MQTT.ordinal()].status);
        assertEquals(3, window.state.rows[Route.MQTT.ordinal()].count);
        PushDiagnostics.mqttState(new FbnsServiceDelegate(), MqttState.ERROR);
        assertEquals(Status.UNKNOWN, window.state.rows[Route.MQTT.ordinal()].status);
    }

    @Test public void gatewayGetterIsAllowlistedAndNeverReadsObjectsOrErrors() {
        assertEquals(Route.MESSAGES, PushDiagnostics.gatewayRoute(new Handler("/fbns_msg")));
        assertEquals(Route.HIGH_PRIORITY, PushDiagnostics.gatewayRoute(new Handler("/fbns_msg_hp")));
        assertEquals(Route.REGISTRATION, PushDiagnostics.gatewayRoute(new Handler("/fbns_reg_req")));
        assertEquals(Route.ACKS, PushDiagnostics.gatewayRoute(new Handler("/fbns_msg_ack")));
        assertNull(PushDiagnostics.gatewayRoute(new Handler("token-or-account-identity")));
        assertNull(PushDiagnostics.gatewayRoute(new Object()));
        assertNull(PushDiagnostics.gatewayRoute(new NotifGatewayStreamHandler() {
            @Override public String getStreamName() { throw new IllegalStateException("secret-error-url"); }
        }));
        assertNull(window.state);
        PushDiagnostics.gatewayReady(new Handler("/fbns_msg"));
        PushDiagnostics.gatewayClosed(new Handler("/fbns_msg"));
        PushDiagnostics.fcmCallback();
        assertEquals(Status.CLOSED, window.state.rows[Route.MESSAGES.ordinal()].status);
        assertEquals(Status.RECEIVED, window.state.rows[Route.FCM_RECEIPT.ordinal()].status);
    }

    @Test public void aLiveProcessDropsPendingRowsWhenTheClockChanges() {
        PushDiagnostics.Window process = new PushDiagnostics.Window();
        process.observe(Route.FCM, Status.ACCEPTED, 1_000, EPOCH + 1_000);
        PushStateStore.State oldBatch = process.take();
        process.observe(Route.MQTT, Status.CONNECTED, 3_000, EPOCH + 303_000);
        process.restore(oldBatch);
        assertEquals(3_000, process.state.startElapsed);
        assertTrue(process.state.historyLost);
        assertEquals(0, process.state.rows[Route.FCM.ordinal()].count);
        assertEquals(1, process.state.rows[Route.MQTT.ordinal()].count);
        process.observe(Route.FCM, Status.FAILED, 20, EPOCH + 86_400_020);
        assertEquals(20, process.state.startElapsed);
        assertEquals(0, process.state.rows[Route.MQTT.ordinal()].count);
        assertEquals(Status.FAILED, process.state.rows[Route.FCM.ordinal()].status);
    }

    @Test public void aFailedWriteRestoresItsBatchWithoutReplacingNewerOutcomes() {
        PushDiagnostics.Window process = new PushDiagnostics.Window();
        process.observe(Route.FCM, Status.ACCEPTED, 1_000, EPOCH + 1_000);
        PushStateStore.State batch = process.take();
        process.observe(Route.FCM, Status.FAILED, 2_000, EPOCH + 2_000);
        process.restore(batch);
        PushStateStore.Row row = process.state.rows[Route.FCM.ordinal()];
        assertEquals(Status.FAILED, row.status);
        assertEquals(2, row.count);
        assertEquals(1_000, row.firstSuccess);
    }

    @Test public void unavailableObserversRecordsAndUnobservedRoutesHaveDifferentWords() {
        boolean[] all = { true, true, true, true };
        PushStateStore.State state = new PushStateStore.State(1_000, EPOCH + 1_000);
        state.boot = 7;
        List<String> normal = PushDiagnostics.report(state, 5_000, all, false);
        assertTrue(normal.toString().contains("not observed in this window"));
        assertTrue(normal.toString().contains("began 4 s before this report, in this Android boot"));
        assertTrue(normal.toString().contains("do not measure sign-in or delivery"));
        state.boot = -1;
        assertTrue(PushDiagnostics.report(state, 5_000, all, false).toString().contains("boot counter unavailable"));
        assertTrue(PushDiagnostics.report(null, 5_000, all, false).toString().contains("unknown (records unavailable)"));
        assertTrue(PushDiagnostics.report(state, 5_000, new boolean[4], false).toString().contains("observer unavailable in this build"));
        state.historyLost = true;
        assertFalse(PushDiagnostics.report(state, 5_000, all, false).toString().contains("not observed in this window"));
        state.rows[Route.MESSAGES.ordinal()].add(Status.CONNECTED, 2_000, 1, 2_000);
        String observed = PushDiagnostics.report(state, 5_000, all, true).toString();
        assertTrue(observed.contains("stream ready"));
        assertTrue(observed.contains("first positive outcome +1 s"));
        assertTrue(observed.contains("updates are pending"));
        assertTrue(observed.contains("Notification display is unverified"));
        assertFalse(observed.contains("never ran"));
    }
}
