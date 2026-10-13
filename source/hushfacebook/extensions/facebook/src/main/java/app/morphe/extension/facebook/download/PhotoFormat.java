/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.os.Build;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;

/**
 * Saved photos are JPEGs, the way Facebook's own Save photo writes them.
 *
 * <p>Meta's CDN answers the bigger copy {@link PhotoSave} asks for as an AVIF image (S25, Facebook
 * 581, 2026-10-08: a NASA photo came as a 1536x2048 AVIF where the largest copy and Facebook's
 * own save were JPEGs). Android reads AVIF only from 12 on, and plenty of apps still don't, so a
 * photo save that comes back as AVIF is decoded and written again as a JPEG before it's published.
 * On Android 11, which can't decode AVIF at all, {@link #readsAvif} says no and the save keeps the
 * largest copy instead.
 */
final class PhotoFormat {
    private PhotoFormat() {}

    static final String AVIF = "image/avif";
    static final String JPEG = "image/jpeg";

    /** The JPEG quality of a converted photo. High, as the source is already compressed once. */
    static final int QUALITY = 95;

    /** A decoder in Android's place, for tests, where AVIF can't be decoded. */
    @Nullable
    static volatile Decoder decoderForTests;

    /** Whether this phone reads AVIF, in place of the Android version, for tests. */
    @Nullable
    static volatile Boolean readsAvifForTests;

    interface Decoder {
        Bitmap decode(File file) throws IOException;
    }

    /** Whether this phone can decode an AVIF image, and so turn one into a JPEG: Android 12 and later. */
    static boolean readsAvif() {
        Boolean forced = readsAvifForTests;
        return forced != null ? forced : Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /**
     * Rewrites [file], an image of type [mime], as a JPEG when it's an AVIF, and answers the type it
     * holds afterwards. Anything else, or a conversion that fails, leaves the file as it was and
     * answers [mime]. Blocking. Never throws.
     *
     * <p>The JPEG is a work file of its own beside [file] ({@link DashSave#reserve}), given room for
     * the picture's own pixels at most and never more than [maxBytes], the cap the photo was fetched
     * under. It stops at that room, and takes the AVIF's place only once it's whole and inside it. A
     * JPEG that doesn't fit leaves the AVIF, which did.
     */
    static String jpegFromAvif(File file, @Nullable String mime, long maxBytes) {
        if (!AVIF.equals(mime)) return mime;
        File jpeg = new File(file.getPath() + ".jpg");
        Bitmap bitmap = null;
        try {
            bitmap = decode(file);
            // A JPEG at this quality comes out well under the pixels' own bytes.
            long room = DashSave.reserve(jpeg, Math.min(maxBytes, bitmap.getAllocationByteCount() + OutputAllowance.BOXES));
            if (room <= 0) throw new IOException("no free space for the JPEG");
            try (OutputStream out = new Capped(new FileOutputStream(jpeg), room)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) throw new IOException("compress said no");
            }
            // Checked again whole, as the file it replaces was when it was fetched.
            long made = jpeg.length();
            if (made > room || made > maxBytes) throw new IOException("the JPEG is " + made + " bytes, more than the " + room + " kept for it");
            Files.move(jpeg.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, PhotoSave.SOURCE,
                () -> "the photo came as AVIF, saved as a " + width + "x" + height + " JPEG");
            return JPEG;
        } catch (Throwable t) {
            // An out-of-memory error lands here too; the AVIF is still whole and goes as it came.
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, PhotoSave.SOURCE,
                () -> "the photo came as AVIF and couldn't be made a JPEG, so it's saved as AVIF: " + t.getClass().getSimpleName());
            return mime;
        } finally {
            if (bitmap != null) bitmap.recycle();
            // Its room goes back, and the JPEG with it when it never took the AVIF's place.
            DashSave.discard(jpeg);
        }
    }

    /** Passes bytes on until [room] of them have gone, and throws rather than pass one more. */
    private static final class Capped extends FilterOutputStream {
        private long left;

        Capped(OutputStream out, long room) {
            super(out);
            left = room;
        }

        @Override
        public void write(int b) throws IOException {
            if (left < 1) throw over();
            out.write(b);
            left--;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > left) throw over();
            out.write(bytes, offset, length);
            left -= length;
        }

        private static IOException over() {
            return new OutputAllowance.Exceeded("the JPEG would pass the room kept for it");
        }
    }

    private static Bitmap decode(File file) throws IOException {
        Decoder forced = decoderForTests;
        if (forced != null) return forced.decode(file);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw new IOException("Android " + Build.VERSION.SDK_INT + " reads no AVIF");
        // Software memory, so compress can read the pixels; the decoder applies the image's rotation.
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file),
            (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
    }
}
