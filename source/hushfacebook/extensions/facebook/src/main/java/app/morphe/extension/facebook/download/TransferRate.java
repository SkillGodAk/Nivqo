/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

/**
 * How long one save has run and how fast it is downloading, on a monotonic clock in nanoseconds,
 * so a change of the phone's time can't make either jump.
 *
 * <p>The rate is the bytes moved over about the last {@link #WINDOW} nanoseconds, measured up to
 * now rather than up to the last read, so a download that stalls drops to nothing within the
 * window instead of showing its last speed. It needs {@link #SHORTEST} of reading before it says
 * anything, since two reads a millisecond apart would claim a speed the line never had. A counter
 * that goes back, as when the single file is fetched from the start after a join fails, starts the
 * measurement over rather than give a negative rate, and so does a phase that moves no bytes. At
 * most {@link #KEPT} samples are held, one per {@link #SPACING}.
 */
final class TransferRate {
    static final long WINDOW = 5_000_000_000L;
    static final long SHORTEST = 1_000_000_000L;
    static final long SPACING = 250_000_000L;
    /** Eight seconds at the spacing: more than the window, so a sample from before it is still there. */
    static final int KEPT = 32;

    private final long started;
    private final long[] at = new long[KEPT];
    private final long[] bytes = new long[KEPT];
    private int first;
    private int count;
    private long latest;
    /**
     * When [latest] last grew. A read between samples only moves [latest], so after a stall the
     * newest sample can be behind it for good, and only this says nothing came for a whole window.
     */
    private long grewAt;

    TransferRate(long now) {
        started = now;
    }

    /** Milliseconds since the save began, never negative. */
    long elapsedMillis(long now) {
        return Math.max(0L, (now - started) / 1_000_000L);
    }

    /** [done] bytes of the current download had arrived at [now]. */
    synchronized void sample(long now, long done) {
        if (done < 0) return;
        if (count > 0 && done < latest) count = 0;
        if (count == 0 || done > latest) grewAt = now;
        latest = done;
        if (count > 0 && now - at[index(count - 1)] < SPACING) return;
        if (count == KEPT) {
            first = index(1);
            count--;
        }
        int next = index(count++);
        at[next] = now;
        bytes[next] = done;
    }

    /** Joining, copying and publishing move no download bytes: the next download measures afresh. */
    synchronized void pause() {
        count = 0;
    }

    /** Bytes a second over the window up to [now], 0 when nothing moved, or -1 before it can say. */
    synchronized long bytesPerSecond(long now) {
        if (count == 0) return -1;
        int base = index(0);
        for (int k = 1; k < count && at[index(k)] <= now - WINDOW; k++) base = index(k);
        long span = now - at[base];
        if (span < SHORTEST) return -1;
        if (now - grewAt >= WINDOW) return 0;
        long moved = latest - bytes[base];
        if (moved <= 0) return 0;
        return (long) (moved * 1e9 / span);
    }

    private int index(int k) {
        return (first + k) % KEPT;
    }
}
