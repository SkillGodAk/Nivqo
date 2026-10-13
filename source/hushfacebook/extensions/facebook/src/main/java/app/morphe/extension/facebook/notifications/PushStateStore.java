/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.notifications;

import android.os.SystemClock;
import android.util.AtomicFile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.function.LongSupplier;

import static app.morphe.extension.facebook.notifications.PushDiagnostics.Route;
import static app.morphe.extension.facebook.notifications.PushDiagnostics.Status;

/** Fixed numeric records only. A separate lock serializes AtomicFile across Facebook processes. */
final class PushStateStore {
    static final String FILE = "hush_push_v1.bin";
    static final int MAX_COUNT = 10_000;
    private static final int MAGIC = 0x48505553;
    static final int LENGTH = 29 + Route.values().length * 32;
    private static final long CLOCK_TOLERANCE_MS = 120_000;

    static final class Row {
        Status status = Status.NOT_OBSERVED;
        int count;
        long first = -1, last = -1, firstSuccess = -1;

        void add(Status value, long at, int occurrences, long earliestSuccess) {
            if (count == 0 || at >= last) status = value;
            first = first < 0 ? at : Math.min(first, at);
            last = Math.max(last, at);
            count = Math.min(MAX_COUNT, count + Math.min(MAX_COUNT, occurrences));
            if (earliestSuccess >= 0) firstSuccess = firstSuccess < 0 ? earliestSuccess : Math.min(firstSuccess, earliestSuccess);
        }

        void merge(Row other) {
            if (other.count == 0) return;
            add(other.status, other.last, other.count, other.firstSuccess);
            first = Math.min(first, other.first);
        }
    }

    static final class State {
        long startElapsed, startWall;
        int boot = -1;
        boolean historyLost;
        final Row[] rows = rows();

        State(long elapsed, long wall) { startElapsed = elapsed; startWall = wall; }

        boolean sameClock(long elapsed, long wall) {
            if (!sameOrigin(elapsed, wall)) return false;
            for (Row row : rows) if (row.last > elapsed) return false;
            return true;
        }

        boolean sameOrigin(long elapsed, long wall) {
            long shift = (wall - elapsed) - (startWall - startElapsed);
            return elapsed >= startElapsed && shift >= -CLOCK_TOLERANCE_MS && shift <= CLOCK_TOLERANCE_MS;
        }
    }

    static Row[] rows() {
        Row[] result = new Row[Route.values().length];
        for (int i = 0; i < result.length; i++) result[i] = new Row();
        return result;
    }

    private final File directory;
    private final int boot;
    private final LongSupplier elapsedClock, wallClock;

    PushStateStore(File directory, int boot) {
        this(directory, boot, SystemClock::elapsedRealtime, System::currentTimeMillis);
    }

    PushStateStore(File directory, int boot, LongSupplier elapsedClock, LongSupplier wallClock) {
        this.directory = directory;
        this.boot = boot;
        this.elapsedClock = elapsedClock;
        this.wallClock = wallClock;
    }

    State read() throws IOException { return access(null); }

    State merge(State updates) throws IOException { return access(updates); }

    private State access(State updates) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Private push directory unavailable");
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(directory, "hush_push_v1.lock"), "rw")) {
            FileLock lock;
            try { lock = lockFile.getChannel().tryLock(); }
            catch (OverlappingFileLockException busy) { throw new IOException("Push records busy"); }
            if (lock == null) throw new IOException("Push records busy");
            try {
                long elapsed = elapsedClock.getAsLong(), wall = wallClock.getAsLong();
                AtomicFile file = new AtomicFile(new File(directory, FILE));
                boolean current = updates != null && updates.sameClock(elapsed, wall);
                long startElapsed = current ? updates.startElapsed : elapsed;
                long startWall = current ? updates.startWall : wall;
                State state;
                boolean loaded = false;
                try { state = decode(file); loaded = true; }
                catch (FileNotFoundException absent) {
                    if (updates == null) throw absent;
                    state = new State(startElapsed, startWall);
                }
                catch (IOException malformed) {
                    if (updates == null) throw malformed;
                    state = new State(startElapsed, startWall);
                    state.historyLost = true;
                }
                if (!state.sameClock(elapsed, wall) || (loaded && boot >= 0 && boot != state.boot)) {
                    if (updates == null) throw new IOException("Push observation clock changed");
                    state = new State(startElapsed, startWall);
                    state.historyLost = true;
                }
                state.boot = boot;
                if (updates != null) {
                    if (current && startElapsed < state.startElapsed) {
                        state.startElapsed = startElapsed;
                        state.startWall = startWall;
                    }
                    state.historyLost |= updates.historyLost || !current;
                    if (current) for (int i = 0; i < updates.rows.length; i++) state.rows[i].merge(updates.rows[i]);
                    encode(file, state);
                }
                return state;
            } finally { lock.release(); }
        }
    }

    private static State decode(AtomicFile file) throws IOException {
        try (DataInputStream in = new DataInputStream(file.openRead())) {
            if (in.readInt() != MAGIC || in.readInt() != 1) throw new IOException("Unknown push record format");
            State state = new State(in.readLong(), in.readLong());
            state.boot = in.readInt();
            state.historyLost = in.readBoolean();
            if (state.startElapsed < 0 || state.startWall < 0 || state.boot < -1) throw new IOException("Invalid push observation window");
            for (Route route : Route.values()) {
                Row row = state.rows[route.ordinal()];
                int status = in.readInt();
                row.count = in.readInt();
                row.first = in.readLong();
                row.last = in.readLong();
                row.firstSuccess = in.readLong();
                if (status < 0 || status >= Status.values().length) throw new IOException("Invalid push status");
                row.status = Status.values()[status];
                if (row.count < 0 || row.count > MAX_COUNT || !route.allows(row.status)
                        || (row.count == 0 && (row.status != Status.NOT_OBSERVED || row.first != -1 || row.last != -1 || row.firstSuccess != -1))
                        || (row.count > 0 && (row.status == Status.NOT_OBSERVED || row.first < state.startElapsed || row.last < row.first))
                        || (row.firstSuccess != -1 && (row.firstSuccess < row.first || row.firstSuccess > row.last))) {
                    throw new IOException("Invalid push record");
                }
            }
            if (in.read() != -1) throw new IOException("Oversized push records");
            return state;
        }
    }

    private static void encode(AtomicFile file, State state) throws IOException {
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            DataOutputStream out = new DataOutputStream(stream);
            out.writeInt(MAGIC);
            out.writeInt(1);
            out.writeLong(state.startElapsed);
            out.writeLong(state.startWall);
            out.writeInt(state.boot);
            out.writeBoolean(state.historyLost);
            for (Row row : state.rows) {
                out.writeInt(row.status.ordinal());
                out.writeInt(row.count);
                out.writeLong(row.first);
                out.writeLong(row.last);
                out.writeLong(row.firstSuccess);
            }
            out.flush();
            file.finishWrite(stream);
        } catch (IOException failure) {
            if (stream != null) file.failWrite(stream);
            throw failure;
        }
    }
}
