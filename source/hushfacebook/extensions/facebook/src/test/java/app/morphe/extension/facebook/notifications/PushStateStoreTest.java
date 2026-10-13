/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.notifications;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;

import static app.morphe.extension.facebook.notifications.PushDiagnostics.Route;
import static app.morphe.extension.facebook.notifications.PushDiagnostics.Status;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PushStateStoreTest {
    private static final long EPOCH = 1_800_000_000_000L;
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private static final class Store {
        private final AtomicLong elapsed = new AtomicLong(), wall = new AtomicLong();
        private final PushStateStore delegate;

        Store(File directory) { this(directory, -1); }
        Store(File directory, int boot) {
            delegate = new PushStateStore(directory, boot, elapsed::get, wall::get);
        }
        PushStateStore.State read(long elapsed, long wall) throws IOException {
            this.elapsed.set(elapsed);
            this.wall.set(wall);
            return delegate.read();
        }
        PushStateStore.State merge(PushStateStore.State updates, long elapsed, long wall) throws IOException {
            this.elapsed.set(elapsed);
            this.wall.set(wall);
            return delegate.merge(updates);
        }
    }

    @Test public void samplingTheClockCannotLetAnotherWriterCommitBetweenTheSampleAndRead() throws Exception {
        File directory = temp.newFolder();
        Store other = new Store(directory, 7);
        other.merge(batch(1_000, Route.FCM, Status.ACCEPTED, 1_100), 1_200, EPOCH + 1_200);
        PushStateStore delayed = new PushStateStore(directory, 7, () -> {
            assertThrows(IOException.class, () -> other.merge(
                    batch(1_000, Route.MQTT, Status.CONNECTED, 2_000), 2_100, EPOCH + 2_100));
            return 1_500;
        }, () -> EPOCH + 1_500);
        PushStateStore.State merged = delayed.merge(batch(1_000, Route.FCM, Status.FAILED, 1_400));
        assertFalse(merged.historyLost);
        assertEquals(2, merged.rows[Route.FCM.ordinal()].count);
        assertEquals(Status.FAILED, merged.rows[Route.FCM.ordinal()].status);
        assertEquals(0, merged.rows[Route.MQTT.ordinal()].count);
        assertFalse(delayed.read().historyLost);
    }

    @Test public void anEarlierCallbackBatchPreservesANewerCommittedOutcome() throws Exception {
        File directory = temp.newFolder();
        Store other = new Store(directory, 7);
        PushStateStore.State earlier = batch(1_000, Route.FCM, Status.FAILED, 1_400);
        other.merge(batch(1_000, Route.FCM, Status.ACCEPTED, 2_000), 2_100, EPOCH + 2_100);
        PushStateStore delayed = new PushStateStore(directory, 7, () -> 2_200, () -> EPOCH + 2_200);
        PushStateStore.State merged = delayed.merge(earlier);
        assertFalse(merged.historyLost);
        assertEquals(1_000, merged.startElapsed);
        assertEquals(2, merged.rows[Route.FCM.ordinal()].count);
        assertEquals(1_400, merged.rows[Route.FCM.ordinal()].first);
        assertEquals(2_000, merged.rows[Route.FCM.ordinal()].last);
        assertEquals(2_000, merged.rows[Route.FCM.ordinal()].firstSuccess);
        assertEquals(Status.ACCEPTED, merged.rows[Route.FCM.ordinal()].status);
    }

    private PushStateStore.State batch(long start, Route route, Status status, long at) {
        PushStateStore.State value = new PushStateStore.State(start, EPOCH + start);
        value.rows[route.ordinal()].add(status, at, 1, status == Status.ACCEPTED || status == Status.CONNECTED ? at : -1);
        return value;
    }

    @Test public void independentProcessesMergeWithoutLosingTheFirstOutcomeOrWindow() throws Exception {
        File directory = temp.newFolder();
        Store main = new Store(directory);
        Store notifications = new Store(directory);
        notifications.merge(batch(1_000, Route.FCM, Status.ACCEPTED, 1_100), 1_200, EPOCH + 1_200);
        main.merge(batch(1_500, Route.MQTT, Status.CONNECTED, 1_600), 1_700, EPOCH + 1_700);
        notifications.merge(batch(1_000, Route.FCM, Status.FAILED, 2_000), 2_100, EPOCH + 2_100);
        PushStateStore.State state = new Store(directory).read(3_000, EPOCH + 3_000);
        assertEquals(1_000, state.startElapsed);
        assertEquals(Status.FAILED, state.rows[Route.FCM.ordinal()].status);
        assertEquals(2, state.rows[Route.FCM.ordinal()].count);
        assertEquals(1_100, state.rows[Route.FCM.ordinal()].firstSuccess);
        assertEquals(Status.CONNECTED, state.rows[Route.MQTT.ordinal()].status);
        assertEquals(PushStateStore.LENGTH, new File(directory, PushStateStore.FILE).length());
    }

    @Test public void lockContentionLeavesTheOtherProcessesRecordsIntact() throws Exception {
        File directory = temp.newFolder();
        Store store = new Store(directory);
        store.merge(batch(1_000, Route.FCM, Status.ACCEPTED, 1_100), 1_200, EPOCH + 1_200);
        byte[] before = Files.readAllBytes(new File(directory, PushStateStore.FILE).toPath());
        try (RandomAccessFile file = new RandomAccessFile(new File(directory, "hush_push_v1.lock"), "rw");
             FileLock held = file.getChannel().lock()) {
            assertThrows(IOException.class, () -> new Store(directory).read(1_300, EPOCH + 1_300));
            assertThrows(IOException.class, () -> new Store(directory).merge(
                    batch(1_000, Route.FCM, Status.FAILED, 1_300), 1_400, EPOCH + 1_400));
            assertArrayEquals(before, Files.readAllBytes(new File(directory, PushStateStore.FILE).toPath()));
        }
        assertEquals(Status.ACCEPTED, store.read(1_500, EPOCH + 1_500).rows[Route.FCM.ordinal()].status);
    }

    @Test public void unreadableOrMissingRecordsNeverMeanTheCallbackDidNotRun() throws Exception {
        File directory = temp.newFolder();
        Store store = new Store(directory);
        assertThrows(IOException.class, () -> store.read(1_000, EPOCH + 1_000));
        String secret = "token/account/payload/error-must-never-be-exported";
        Files.write(new File(directory, PushStateStore.FILE).toPath(), secret.getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> store.read(1_000, EPOCH + 1_000));
        PushStateStore.State state = store.merge(new PushStateStore.State(1_000, EPOCH + 1_000), 1_100, EPOCH + 1_100);
        String report = PushDiagnostics.report(state, 2_000, new boolean[] {true, true, true, true}, false).toString();
        assertTrue(state.historyLost);
        assertTrue(report.contains("unknown (records unavailable)"));
        assertFalse(report.contains("not observed in this window"));
        assertFalse(report.contains(secret));
        assertEquals(PushStateStore.LENGTH, new File(directory, PushStateStore.FILE).length());
        assertFalse(new String(Files.readAllBytes(new File(directory, PushStateStore.FILE).toPath()), StandardCharsets.UTF_8).contains(secret));
    }

    @Test public void aRebootOrClockJumpDiscardsThePreviousEpochAndStaysReadable() throws Exception {
        File directory = temp.newFolder();
        Store store = new Store(directory);
        store.merge(batch(1_000, Route.FCM, Status.ACCEPTED, 1_100), 1_200, EPOCH + 1_200);
        // This same live collector still has a pre-jump batch. It must be dropped, not re-dated.
        PushStateStore.State jumped = store.merge(batch(1_000, Route.MQTT, Status.CONNECTED, 1_300), 1_400, EPOCH + 301_400);
        assertTrue(jumped.historyLost);
        assertEquals(1_400, jumped.startElapsed);
        assertEquals(0, jumped.rows[Route.FCM.ordinal()].count);
        assertEquals(0, jumped.rows[Route.MQTT.ordinal()].count);
        assertTrue(store.read(1_500, EPOCH + 301_500).historyLost);
        PushStateStore.State freshBoot = new PushStateStore.State(20, EPOCH + 86_400_020);
        freshBoot.rows[Route.FCM.ordinal()].add(Status.FAILED, 30, 1, -1);
        store.merge(freshBoot, 40, EPOCH + 86_400_040);
        PushStateStore.State read = store.read(50, EPOCH + 86_400_050);
        assertEquals(20, read.startElapsed);
        assertEquals(Status.FAILED, read.rows[Route.FCM.ordinal()].status);
        assertEquals(-1, read.rows[Route.FCM.ordinal()].firstSuccess);
    }

    @Test public void retainedRecordsAreFixedSizeAndCountersSaturate() throws Exception {
        File directory = temp.newFolder();
        Store store = new Store(directory);
        PushStateStore.State updates = batch(1_000, Route.FCM, Status.ACCEPTED, 1_100);
        updates.rows[Route.FCM.ordinal()].add(Status.REJECTED, 1_200, Integer.MAX_VALUE, -1);
        store.merge(updates, 1_300, EPOCH + 1_300);
        PushStateStore.State state = store.read(1_400, EPOCH + 1_400);
        assertEquals(PushStateStore.MAX_COUNT, state.rows[Route.FCM.ordinal()].count);
        assertEquals(1_100, state.rows[Route.FCM.ordinal()].firstSuccess);
        assertTrue(PushDiagnostics.report(state, 2_000, new boolean[] {true, true, true, true}, false).toString().contains("observations 10000+"));
        assertEquals(PushStateStore.LENGTH, new File(directory, PushStateStore.FILE).length());
    }

    @Test public void aQuickRebootCannotReuseThePreviousBootsPositiveResult() throws Exception {
        File directory = temp.newFolder();
        new Store(directory, 7).merge(batch(1_000, Route.FCM, Status.ACCEPTED, 1_100), 1_200, EPOCH + 1_200);
        Store nextBoot = new Store(directory, 8);
        // A short reboot can fit the wall-clock tolerance and even have a larger current uptime.
        assertThrows(IOException.class, () -> nextBoot.read(2_000, EPOCH + 32_000));
        PushStateStore.State fresh = new PushStateStore.State(2_000, EPOCH + 32_000);
        nextBoot.merge(fresh, 2_100, EPOCH + 32_100);
        PushStateStore.State state = nextBoot.read(2_200, EPOCH + 32_200);
        assertEquals(8, state.boot);
        assertEquals(0, state.rows[Route.FCM.ordinal()].count);
        assertEquals(-1, state.rows[Route.FCM.ordinal()].firstSuccess);
    }

    @Test public void aFutureOutcomeInvalidatesTheEpochWhenTheBootCounterIsUnavailable() throws Exception {
        File directory = temp.newFolder();
        new Store(directory).merge(batch(1_000, Route.FCM, Status.ACCEPTED, 60_000), 61_000, EPOCH + 61_000);
        Store nextBoot = new Store(directory);
        // Its early origin still fits, but its late outcome is beyond this boot's elapsed clock.
        assertThrows(IOException.class, () -> nextBoot.read(31_000, EPOCH + 40_000));
        PushStateStore.State state = nextBoot.merge(new PushStateStore.State(31_000, EPOCH + 40_000), 32_000, EPOCH + 41_000);
        assertEquals(31_000, state.startElapsed);
        assertTrue(state.historyLost);
        assertEquals(0, state.rows[Route.FCM.ordinal()].count);
        String report = PushDiagnostics.report(nextBoot.read(33_000, EPOCH + 42_000), 33_000,
                new boolean[] {true, true, true, true}, false).toString();
        assertTrue(report.contains("Earlier observations are unavailable"));
        assertFalse(report.contains("first positive outcome"));
    }

    @Test public void newlyAvailableBootCounterCannotCertifyAnOlderUnverifiedWindow() throws Exception {
        File directory = temp.newFolder();
        new Store(directory).merge(batch(1_000, Route.FCM, Status.ACCEPTED, 1_100), 1_200, EPOCH + 1_200);
        Store knownBoot = new Store(directory, 9);
        assertThrows(IOException.class, () -> knownBoot.read(1_300, EPOCH + 1_300));
        PushStateStore.State state = knownBoot.merge(new PushStateStore.State(1_300, EPOCH + 1_300), 1_400, EPOCH + 1_400);
        assertTrue(state.historyLost);
        assertEquals(0, state.rows[Route.FCM.ordinal()].count);
        assertEquals(9, knownBoot.read(1_500, EPOCH + 1_500).boot);
    }

    @Test public void impossibleRouteStatusesAndTrailingDataAreRejected() throws Exception {
        File directory = temp.newFolder();
        Store store = new Store(directory);
        store.merge(batch(1_000, Route.FCM, Status.ACCEPTED, 1_100), 1_200, EPOCH + 1_200);
        File records = new File(directory, PushStateStore.FILE);
        try (RandomAccessFile file = new RandomAccessFile(records, "rw")) {
            file.seek(29);
            file.writeInt(Status.CONNECTED.ordinal());
        }
        assertThrows(IOException.class, () -> store.read(1_300, EPOCH + 1_300));
        store.merge(batch(1_300, Route.MQTT, Status.CONNECTED, 1_300), 1_400, EPOCH + 1_400);
        try (RandomAccessFile file = new RandomAccessFile(records, "rw")) {
            file.seek(file.length());
            file.write(1);
        }
        assertThrows(IOException.class, () -> store.read(1_500, EPOCH + 1_500));
    }
}
