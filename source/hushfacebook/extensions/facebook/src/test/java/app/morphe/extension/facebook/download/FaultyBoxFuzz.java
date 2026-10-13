/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import java.nio.ByteBuffer;

/**
 * A box reader with a planted bug, for scripts/fuzz-parsers.ps1 to find before it trusts a clean
 * run of the real targets. It walks MP4 boxes and checks each length, except a moov's, which it
 * trusts: a moov longer than what's left reads past the end. Test code only, never a parser.
 */
public final class FaultyBoxFuzz {

    private FaultyBoxFuzz() {}

    static final int MOOV = 0x6D6F6F76;

    public static void fuzzerTestOneInput(byte[] data) {
        ByteBuffer in = ByteBuffer.wrap(data);
        while (in.remaining() >= 8) {
            long size = in.getInt() & 0xFFFFFFFFL;
            int type = in.getInt();
            if (type == MOOV) {
                // The planted bug: no check that the moov fits.
                byte[] body = new byte[(int) Math.max(0, Math.min(size - 8, 1 << 20))];
                in.get(body);
                continue;
            }
            if (size < 8 || size - 8 > in.remaining()) return;
            in.position(in.position() + (int) (size - 8));
        }
    }
}
