/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import java.io.File;
import java.io.IOException;

/**
 * The room a conversion's output file was given ({@link DashSave#reserve}), counted as it's written.
 *
 * <p>A muxer writes each sample's bytes as it gets them, and the tables that list the samples and
 * the file's header when it stops. So each sample is counted with its table entries, and the
 * header's room is kept from the start. The first sample that wouldn't fit ends the conversion
 * before the muxer has it, and the finished file is held to the room as well. A conversion can't
 * then take the free space another save was given, or what every save leaves free.
 */
final class OutputAllowance {
    /** Room for the header and the fixed part of each table, kept from the first sample. */
    static final long BOXES = 64L * 1024L;

    /** Room each sample takes in the tables: its size, time, sync and chunk entries, rounded up. */
    static final long PER_SAMPLE = 32L;

    /** The most the file may hold. */
    final long room;

    /** What the samples so far and the boxes they need come to. */
    private long used = BOXES;

    OutputAllowance(long room) {
        this.room = room;
    }

    /** Counts a sample of [size] bytes, or throws when the file would pass its room with it. */
    void take(long size) throws Exceeded {
        long next = used + Math.max(0L, size) + PER_SAMPLE;
        if (next > room) throw new Exceeded("the output would pass the " + room + " bytes kept for it");
        used = next;
    }

    /** Throws when [file], finished, is larger than its room. */
    void check(File file) throws Exceeded {
        long length = file.length();
        if (length > room) throw new Exceeded("the output is " + length + " bytes, more than the " + room + " kept for it");
    }

    /** A conversion stopped because its file wouldn't fit the room it was given. */
    static final class Exceeded extends IOException {
        Exceeded(String message) {
            super(message);
        }
    }
}
