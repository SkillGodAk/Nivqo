package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Every save slot taken by saves that wait until this closes, for a test of what a tap does while
 * all of them are busy. Closing lets them end as cancelled and waits for their threads.
 */
final class SaveSlotsForTests implements AutoCloseable {
    /** How many saves run at once, as MediaDownload allows. */
    static final int SLOTS = 3;

    private final CountDownLatch release = new CountDownLatch(1);
    private final List<Thread> held = new ArrayList<>();

    private SaveSlotsForTests() {
    }

    static SaveSlotsForTests holdAll(Context context) throws InterruptedException {
        SaveSlotsForTests slots = new SaveSlotsForTests();
        try {
            for (int i = 0; i < SLOTS; i++) {
                CountDownLatch entered = new CountDownLatch(1);
                Thread worker = MediaDownload.start(context, false, (writer, progress) -> {
                    entered.countDown();
                    try {
                        slots.release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    }
                    return Downloader.Result.fail(Downloader.Status.CANCELLED, "test job ended");
                });
                assertNotNull("a holding save didn't start", worker);
                slots.held.add(worker);
                assertTrue("a holding save never ran", entered.await(10, TimeUnit.SECONDS));
            }
            assertEquals(SLOTS, MediaDownload.savesInFlight());
            return slots;
        } catch (Throwable failure) {
            slots.close();
            throw failure;
        }
    }

    @Override
    public void close() throws InterruptedException {
        release.countDown();
        for (Thread worker : held) worker.join(10_000);
    }
}
