/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A save's rate on a clock the test sets: nothing before a second of reading, the bytes over the
 * last five seconds after that, nothing once a stall outlasts the window, and never a negative or
 * inflated number when the counter starts over or a phase moves no bytes.
 */
public class TransferRateTest {
    private static final long MS = 1_000_000L;
    private static final long MIB = 1024L * 1024L;
    private static final long T0 = 10_000 * MS;

    /** A megabyte a second from [from] to [to] milliseconds, a read every 50 ms, counting from [base] bytes. */
    private static void steady(TransferRate rate, long from, long to, long base) {
        for (long ms = from; ms <= to; ms += 50) rate.sample(T0 + ms * MS, base + (ms - from) * MIB / 1000);
    }

    @Test public void zeroBytesHaveNoSpeed() {
        TransferRate rate = new TransferRate(T0);
        rate.sample(T0, 0);
        rate.sample(T0 + 1_500 * MS, 0);
        assertEquals(0, rate.bytesPerSecond(T0 + 1_500 * MS));
    }

    @Test public void nothingIsMeasuredBeforeTheFirstReadOrUnderASecond() {
        TransferRate rate = new TransferRate(T0);
        assertEquals(-1, rate.bytesPerSecond(T0 + 5_000 * MS));
        rate.sample(T0, 0);
        rate.sample(T0 + 1 * MS, MIB);
        assertEquals("two reads a millisecond apart claimed a speed", -1, rate.bytesPerSecond(T0 + 1 * MS));
        assertEquals(-1, rate.bytesPerSecond(T0 + 999 * MS));
        assertEquals(MIB, rate.bytesPerSecond(T0 + 1_000 * MS));
    }

    @Test public void aSteadyDownloadReadsItsSpeedOverTheWindow() {
        TransferRate rate = new TransferRate(T0);
        steady(rate, 0, 20_000, 0);
        assertEquals(MIB, rate.bytesPerSecond(T0 + 20_000 * MS));
    }

    @Test public void aStallFallsToNothingWithinTheWindow() {
        TransferRate rate = new TransferRate(T0);
        steady(rate, 0, 10_000, 0);
        long falling = rate.bytesPerSecond(T0 + 12_000 * MS);
        assertTrue("a two second stall still read " + falling, falling > 0 && falling < MIB);
        assertEquals(0, rate.bytesPerSecond(T0 + 15_000 * MS));
        assertEquals(0, rate.bytesPerSecond(T0 + 600_000 * MS));
    }

    /**
     * Real reads seldom land on the sample spacing. The last one before a stall is kept only as the
     * latest count, and the bytes it brought were never in a stored sample, so the rate counted them
     * over an ever longer span and never reached nothing.
     */
    @Test public void aStallAfterAReadBetweenSamplesStillFallsToNothing() {
        TransferRate rate = new TransferRate(T0);
        steady(rate, 0, 10_050, 0);
        assertTrue(rate.bytesPerSecond(T0 + 10_100 * MS) > MIB / 2);
        assertEquals(0, rate.bytesPerSecond(T0 + 15_050 * MS));
        assertEquals(0, rate.bytesPerSecond(T0 + 600_000 * MS));
    }

    @Test public void aCounterThatStartsOverIsMeasuredAfresh() {
        TransferRate rate = new TransferRate(T0);
        steady(rate, 0, 4_000, 0);
        rate.sample(T0 + 4_250 * MS, MIB / 4);
        assertEquals(-1, rate.bytesPerSecond(T0 + 4_250 * MS));
        assertEquals(-1, rate.bytesPerSecond(T0 + 5_000 * MS));
        // Two megabytes a second from the restart on.
        for (long ms = 4_250; ms <= 6_250; ms += 50) rate.sample(T0 + ms * MS, MIB / 4 + (ms - 4_250) * 2 * MIB / 1000);
        assertEquals(2 * MIB, rate.bytesPerSecond(T0 + 6_250 * MS));
    }

    @Test public void aPhaseWithNoDownloadStartsTheRateOverAndKeepsTheTime() {
        TransferRate rate = new TransferRate(T0);
        steady(rate, 0, 3_000, 0);
        rate.pause();
        assertEquals(-1, rate.bytesPerSecond(T0 + 3_000 * MS));
        assertEquals(3_000, rate.elapsedMillis(T0 + 3_000 * MS));
        // The next download carries on from where the bytes were, a megabyte a second again.
        steady(rate, 3_500, 5_000, 3 * MIB);
        assertEquals(MIB, rate.bytesPerSecond(T0 + 5_000 * MS));
    }

    /** A read every millisecond for a minute keeps no more samples than the window needs. */
    @Test public void manyReadsKeepItBounded() {
        TransferRate rate = new TransferRate(T0);
        for (long ms = 0; ms <= 60_000; ms++) rate.sample(T0 + ms * MS, ms * MIB / 1000);
        assertEquals(MIB, rate.bytesPerSecond(T0 + 60_000 * MS));
    }

    @Test public void theTimeNeverGoesBack() {
        TransferRate rate = new TransferRate(T0);
        assertEquals(0, rate.elapsedMillis(T0 - 5 * MS));
        assertEquals(61_500, rate.elapsedMillis(T0 + 61_500 * MS));
    }
}
