/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds {@link Utils}' shared worker pool full, so the next task handed to it is refused, until
 * it's closed.
 *
 * <p>The pool counts as full only when every one of its workers holds a filler and its queue has
 * no room. Filling until the first refusal wasn't enough, even with the pool drained first: the
 * no-op {@link Utils#awaitBackgroundTasksForTests()} waits on is done once its result is set,
 * while its worker can still be on the way back to the queue. The pool then refused a filler with
 * that worker free, the worker took a queued filler, and the queue had room for the task under
 * test. That's how LogBufferManagerClipboardTest's full-queue test failed in the full suite on
 * 2026-09-26. Fillers park on a latch until close() lets them go, and close() waits for all of
 * them to finish.
 *
 * <p>{@link #hold()} keeps the workers busy the same way and leaves the queue room, so the next
 * task is taken and waits its turn until close().
 */
public final class WorkerPoolForTests implements AutoCloseable {
    private final ThreadPoolExecutor pool;
    private final CountDownLatch release = new CountDownLatch(1);
    /** Fillers a worker is running now. */
    private final AtomicInteger parked = new AtomicInteger();
    /** Fillers the pool took and that haven't finished yet. */
    private final AtomicInteger unfinished = new AtomicInteger();

    private WorkerPoolForTests(ThreadPoolExecutor pool) {
        this.pool = pool;
    }

    /** Drains the pool, then fills it until every worker holds a filler and the queue is full. */
    public static WorkerPoolForTests fill() throws Exception {
        WorkerPoolForTests full = new WorkerPoolForTests(pool());
        Utils.awaitBackgroundTasksForTests();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (true) {
                int offered = 0;
                while (full.offer()) {
                    if (++offered > 1000) throw new AssertionError("the worker pool never refused a task");
                }
                if (full.parked.get() == full.pool.getMaximumPoolSize() && full.pool.getQueue().remainingCapacity() == 0) {
                    return full;
                }
                if (System.nanoTime() > deadline) {
                    throw new AssertionError(full.parked.get() + " of " + full.pool.getMaximumPoolSize()
                            + " workers hold a filler, with " + full.pool.getQueue().remainingCapacity()
                            + " places free in the queue");
                }
                // A worker is still finishing something else; it takes a filler when it's done.
                Thread.sleep(2);
            }
        } catch (Throwable failure) {
            full.close();
            throw failure;
        }
    }

    /**
     * Drains the pool, then hands it one filler for each worker it can ever have, with room left
     * in the queue. A task handed over after that is taken and waits until close(): every worker
     * that's free takes a filler ahead of it and parks, and there's a filler for each of them.
     */
    public static WorkerPoolForTests hold() throws Exception {
        WorkerPoolForTests held = new WorkerPoolForTests(pool());
        Utils.awaitBackgroundTasksForTests();
        try {
            for (int filler = 0; filler < held.pool.getMaximumPoolSize(); filler++) {
                if (!held.offer()) throw new AssertionError("the drained worker pool refused a filler");
            }
            return held;
        } catch (Throwable failure) {
            held.close();
            throw failure;
        }
    }

    private static ThreadPoolExecutor pool() throws Exception {
        Field field = Utils.class.getDeclaredField("backgroundThreadPool");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(null);
    }

    private boolean offer() {
        unfinished.incrementAndGet();
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            parked.incrementAndGet();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                parked.decrementAndGet();
                unfinished.decrementAndGet();
            }
        });
        if (!accepted) unfinished.decrementAndGet();
        return accepted;
    }

    /** Lets the fillers go and waits until every one of them has finished. */
    @Override
    public void close() throws Exception {
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (unfinished.get() > 0 && System.nanoTime() < deadline) Thread.sleep(5);
        if (unfinished.get() > 0) throw new AssertionError(unfinished.get() + " fillers never finished");
        Utils.awaitBackgroundTasksForTests();
    }
}
