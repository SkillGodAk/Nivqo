/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The app behind a picked font file, as Android's picker hands one over: it names the file and opens
 * it, each with a cancel signal, and can stall in either the way a slow document app does.
 */
public final class FontFileProvider extends ContentProvider {
    /** Holds every name query until it's counted down, or until the query's signal is cancelled. */
    static volatile CountDownLatch stallQuery;
    /** Holds every open until it's counted down, or until the open's signal is cancelled. */
    static volatile CountDownLatch stallOpen;
    /** A held query or open waits for its latch alone, as an app that ignores the cancel signal does. */
    static volatile boolean ignoresCancel;
    /** Cancels a held query or open received. */
    static final AtomicInteger cancels = new AtomicInteger();
    /** A permit for each query, open or read held, given once it's waiting. */
    static final Semaphore holding = new Semaphore(0);

    /** The file behind each address, by its path. */
    private static final Map<String, File> FILES = new ConcurrentHashMap<>();
    /** The name the app gives each file, by its path. A file with none is named by its address. */
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();
    /** Opens of each file that returned, cancelled ones excluded, by its path. */
    private static final Map<String, AtomicInteger> OPENS = new ConcurrentHashMap<>();

    private static File folder;

    /** Registers the app for [authority] and forgets every file and stall. */
    static void install(String authority) {
        stallQuery = null;
        stallOpen = null;
        ignoresCancel = false;
        cancels.set(0);
        holding.drainPermits();
        FILES.clear();
        NAMES.clear();
        OPENS.clear();
        folder = new File(RuntimeEnvironment.getApplication().getCacheDir(), "font-files-" + System.nanoTime());
        if (!folder.mkdirs()) throw new IllegalStateException("No folder for the test files");
        Robolectric.setupContentProvider(FontFileProvider.class, authority);
    }

    /** Lets every held query and open go, for a test that stops while the app holds one. */
    static void releaseAll() {
        CountDownLatch query = stallQuery;
        if (query != null) query.countDown();
        CountDownLatch open = stallOpen;
        if (open != null) open.countDown();
    }

    /**
     * A file at [address] holding [bytes], ready to be picked, with no opens counted yet. The app
     * names it [name], or nothing when that's null.
     */
    static Uri put(String address, byte[] bytes, String name) throws IOException {
        Uri uri = Uri.parse(address);
        File file = File.createTempFile("font", ".bin", folder);
        Files.write(file.toPath(), bytes);
        FILES.put(uri.getPath(), file);
        if (name == null) NAMES.remove(uri.getPath());
        else NAMES.put(uri.getPath(), name);
        OPENS.put(uri.getPath(), new AtomicInteger());
        return uri;
    }

    /** How many opens of [uri] returned since it was put. */
    static int opens(Uri uri) {
        AtomicInteger count = OPENS.get(uri.getPath());
        return count == null ? 0 : count.get();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order,
                        CancellationSignal signal) {
        hold(stallQuery, signal);
        String name = NAMES.get(uri.getPath());
        if (name == null) return null;
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[]{name});
        return cursor;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        return query(uri, projection, selection, args, order, null);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
        hold(stallOpen, signal);
        File file = FILES.get(uri.getPath());
        if (file == null) throw new FileNotFoundException("No such file");
        ParcelFileDescriptor answer = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        OPENS.computeIfAbsent(uri.getPath(), path -> new AtomicInteger()).incrementAndGet();
        return answer;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return openFile(uri, mode, null);
    }

    // A document app hands the signal on to its open, as DocumentsProvider does; the framework's
    // defaults drop it on the way to openFile.
    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
        return new AssetFileDescriptor(openFile(uri, mode, signal), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts, CancellationSignal signal)
            throws FileNotFoundException {
        return openAssetFile(uri, "r", signal);
    }

    /**
     * Waits on [held] when it's set: until it's counted down, or, for an app that honours it, until
     * [signal] is cancelled.
     */
    private static void hold(CountDownLatch held, CancellationSignal signal) {
        if (held == null) return;
        CountDownLatch cancelled = new CountDownLatch(1);
        if (signal != null) signal.setOnCancelListener(() -> {
            cancels.incrementAndGet();
            cancelled.countDown();
        });
        holding.release();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (!held.await(10, TimeUnit.MILLISECONDS)) {
                if (!ignoresCancel && cancelled.getCount() == 0) throw new OperationCanceledException();
                if (System.nanoTime() > deadline) throw new IllegalStateException("The test never released the app");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new OperationCanceledException();
        }
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "font/ttf"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
