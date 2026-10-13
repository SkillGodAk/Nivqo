/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The report's [NETWORK] section: Facebook's bytes since its start and since it last came to the
 * foreground, read from Android's per-app counters. A phone that doesn't count them, a process
 * that never started counting and counters that start over all say so instead of giving numbers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class NetworkUsageTest {
    private static final long MINUTE = 60_000;

    /** Counters the test sets. */
    private static final class Fake implements NetworkUsage.Counter {
        long received, sent;
        @Override public long received() { return received; }
        @Override public long sent() { return sent; }
        void at(long received, long sent) { this.received = received; this.sent = sent; }
    }

    private final Fake fake = new Fake();

    @Before
    public void setUp() {
        NetworkUsage.resetForTests(fake);
    }

    @After
    public void tearDown() {
        NetworkUsage.resetForTests(null);
    }

    @Test
    public void countsSinceTheStartAndSinceTheLastTimeInTheForeground() {
        fake.at(1_000_000, 200_000);
        NetworkUsage.started(0);
        fake.at(3_000_000, 300_000);
        NetworkUsage.activityStarted(2 * MINUTE);
        fake.at(13_500_000, 1_440_000);
        assertEquals(Arrays.asList(
                "since start (12 min): 12.5 MB received, 1.2 MB sent",
                "this time in the foreground (10 min): 10.5 MB received, 1.1 MB sent"),
                NetworkUsage.lines(12 * MINUTE));
    }

    @Test
    public void onlyComingBackFromTheBackgroundStartsANewForegroundCount() {
        fake.at(0, 0);
        NetworkUsage.started(0);
        NetworkUsage.activityStarted(0);
        fake.at(500_000, 0);
        // A second activity over the first, then it closes: still the same time in the foreground.
        NetworkUsage.activityStarted(MINUTE);
        NetworkUsage.activityStopped(false, MINUTE);
        fake.at(800_000, 40_000);
        assertEquals("this time in the foreground (2 min): 800 KB received, 40 KB sent", NetworkUsage.lines(2 * MINUTE).get(1));

        NetworkUsage.activityStopped(false, 2 * MINUTE);
        fake.at(2_000_000, 50_000);
        NetworkUsage.activityStarted(3 * MINUTE);
        fake.at(2_600_000, 70_000);
        assertEquals(Arrays.asList(
                "since start (1 h 5 min): 2.6 MB received, 70 KB sent",
                "this time in the foreground (1 h 2 min): 600 KB received, 20 KB sent"),
                NetworkUsage.lines(65 * MINUTE));
    }

    @Test
    public void aScreenRebuiltForARotationKeepsTheTimeInTheForeground() {
        fake.at(0, 0);
        NetworkUsage.started(0);
        NetworkUsage.activityStarted(0);
        fake.at(500_000, 10_000);
        // Android stops the old copy before the new one starts.
        NetworkUsage.activityStopped(true, MINUTE - 300);
        NetworkUsage.activityStarted(MINUTE);
        fake.at(900_000, 30_000);
        assertEquals("this time in the foreground (2 min): 900 KB received, 30 KB sent", NetworkUsage.lines(2 * MINUTE).get(1));

        // Leaving for real afterwards still starts a new count on the way back.
        NetworkUsage.activityStopped(false, 2 * MINUTE);
        NetworkUsage.activityStarted(3 * MINUTE);
        fake.at(1_000_000, 40_000);
        assertEquals("this time in the foreground (1 min): 100 KB received, 10 KB sent", NetworkUsage.lines(4 * MINUTE).get(1));
    }

    @Test
    public void aRebuildWhoseNewCopyNeverStartedDoesNotHideTheNextReturn() {
        fake.at(0, 0);
        NetworkUsage.started(0);
        NetworkUsage.activityStarted(0);
        // The old copy stops to be rebuilt, and the new one never starts: the phone went to sleep.
        NetworkUsage.activityStopped(true, MINUTE);
        fake.at(2_000_000, 50_000);
        NetworkUsage.activityStarted(30 * MINUTE);
        fake.at(2_300_000, 60_000);
        assertEquals("this time in the foreground (2 min): 300 KB received, 10 KB sent", NetworkUsage.lines(32 * MINUTE).get(1));
    }

    @Test
    public void aForegroundMarkThePhoneCouldNotCountIsLeftOut() {
        fake.at(0, 0);
        NetworkUsage.started(0);
        fake.at(-1, -1);
        NetworkUsage.activityStarted(MINUTE);
        fake.at(100_000, 1_000);
        assertEquals(Collections.singletonList("since start (2 min): 100 KB received, 1 KB sent"), NetworkUsage.lines(2 * MINUTE));
    }

    @Test
    public void startsCountingOnceAndBeforeAnyActivityHasNoForegroundLine() {
        fake.at(100, 100);
        NetworkUsage.started(0);
        fake.at(9_000_000, 9_000_000);
        NetworkUsage.started(MINUTE);
        assertEquals(Collections.singletonList("since start (under a minute): 9.0 MB received, 9.0 MB sent"),
                NetworkUsage.lines(30_000));
    }

    @Test
    public void saysWhyThereAreNoNumbers() {
        assertEquals(Collections.singletonList("not counted in this process, it starts with Facebook"),
                NetworkUsage.lines(0));

        fake.at(-1, -1);
        NetworkUsage.started(0);
        assertEquals(Collections.singletonList("not available, this phone doesn't count Facebook's traffic"),
                NetworkUsage.lines(MINUTE));

        NetworkUsage.resetForTests(fake);
        fake.at(5_000_000, 5_000_000);
        NetworkUsage.started(0);
        fake.at(1_000, 6_000_000);
        assertEquals(Collections.singletonList("since start (1 min): the phone started its counters over, nothing to compare"),
                NetworkUsage.lines(MINUTE));
    }

    @Test
    public void theReportCarriesTheSectionWithTheAppState() {
        fake.at(0, 0);
        NetworkUsage.started(0);
        LogBufferManager.ReportSection section = NetworkUsage.REPORT;
        assertEquals("NETWORK", section.title());
        assertTrue("network use is app state, shown whatever the event filters say", section.isAppState());
        assertTrue(section.lines().get(0), section.lines().get(0).startsWith("since start ("));
    }

    @Test
    public void sizesAndDurationsReadPlainly() {
        assertEquals("0 KB", NetworkUsage.size(0));
        assertEquals("1 KB", NetworkUsage.size(1_499));
        assertEquals("999 KB", NetworkUsage.size(999_499));
        assertEquals("1.0 MB", NetworkUsage.size(999_999));
        assertEquals("1.0 MB", NetworkUsage.size(1_000_000));
        assertEquals("1234.6 MB", NetworkUsage.size(1_234_567_890));
        assertEquals("under a minute", NetworkUsage.duration(59_999));
        assertEquals("59 min", NetworkUsage.duration(59 * MINUTE + 59_000));
        assertEquals("2 h 0 min", NetworkUsage.duration(120 * MINUTE));
        assertEquals("under a minute", NetworkUsage.duration(-5));
    }
}
