/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import app.morphe.extension.shared.SettingsContextRule;

/**
 * A photo save that comes back as AVIF is written again as a JPEG, and anything else, or a failed
 * conversion, leaves the file as it came. Android's own Bitmap code runs here, so the JPEG is real.
 * AVIF itself can't be decoded off a phone, so a decoder stands in for Android's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhotoFormatTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    /** The start of an AVIF file: an ftyp box naming avif. The decoder never reads past it here. */
    private static final byte[] AVIF_HEAD = "\0\0\0 ftypavif\0\0\0\0avifmif1miaf".getBytes(StandardCharsets.ISO_8859_1);

    /** The cap a photo is fetched under, far above anything here. */
    private static final long CAP = 1L << 30;

    /** Free space past what every save leaves free, for the conversions that fit. */
    private static final long FREE = 64L << 20;

    @Before
    public void setUp() {
        // The test machine's own disk can be nearly full; what's free is the test's to say.
        DashSave.usableForTests = () -> DashSave.KEEP_FREE + FREE;
    }

    @After
    public void tearDown() {
        PhotoFormat.decoderForTests = null;
        PhotoFormat.readsAvifForTests = null;
        DashSave.usableForTests = null;
    }

    private File avif() throws IOException {
        File file = folder.newFile("image.part");
        Files.write(file.toPath(), AVIF_HEAD);
        return file;
    }

    private static File jpegBeside(File file) {
        return new File(file.getPath() + ".jpg");
    }

    private static Bitmap red() {
        Bitmap bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb(200, 40, 40));
        return bitmap;
    }

    /**
     * A JPEG over the cap the photo was fetched under isn't saved: the AVIF, which fit, goes as it
     * came, nothing is left beside it, and the JPEG's room is given back. No JPEG fits in 200
     * bytes, its headers alone being longer.
     */
    @Test
    public void aJpegOverTheCapLeavesTheAvif() throws IOException {
        File file = avif();
        PhotoFormat.decoderForTests = source -> red();
        assertEquals(PhotoFormat.AVIF, PhotoFormat.jpegFromAvif(file, PhotoFormat.AVIF, 200));
        assertArrayEquals(AVIF_HEAD, Files.readAllBytes(file.toPath()));
        assertFalse("a part of the JPEG was left", jpegBeside(file).exists());
        assertFalse("the JPEG kept its room", DashSave.inUse(jpegBeside(file)));

        assertEquals(PhotoFormat.JPEG, PhotoFormat.jpegFromAvif(file, PhotoFormat.AVIF, CAP));
        assertFalse("the JPEG kept its room", DashSave.inUse(jpegBeside(file)));
    }

    /**
     * The JPEG gets only the free space that what every save leaves free and the other running
     * saves' files don't take. With 100 bytes past those, the AVIF is saved as it came, and once
     * the other save is done the JPEG fits.
     */
    @Test
    public void aJpegLeavesTheRoomOtherSavesAndTheFloorAreGiven() throws IOException {
        File file = avif();
        PhotoFormat.decoderForTests = source -> red();
        File other = folder.newFile("video.part");
        DashSave.usableForTests = () -> DashSave.KEEP_FREE + 1_000_000 + 100;
        assertEquals(1_000_000, DashSave.reserve(other, 1_000_000));
        try {
            assertEquals(PhotoFormat.AVIF, PhotoFormat.jpegFromAvif(file, PhotoFormat.AVIF, CAP));
            assertArrayEquals(AVIF_HEAD, Files.readAllBytes(file.toPath()));
            assertFalse("a part of the JPEG was left", jpegBeside(file).exists());
            assertFalse("the JPEG kept its room", DashSave.inUse(jpegBeside(file)));
        } finally {
            DashSave.discard(other);
        }
        assertEquals(PhotoFormat.JPEG, PhotoFormat.jpegFromAvif(file, PhotoFormat.AVIF, CAP));
    }

    @Test
    public void anAvifPhotoIsSavedAsAJpegOfTheSamePicture() throws IOException {
        File file = avif();
        PhotoFormat.decoderForTests = source -> {
            Bitmap bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.rgb(200, 40, 40));
            return bitmap;
        };
        assertEquals(PhotoFormat.JPEG, PhotoFormat.jpegFromAvif(file, PhotoFormat.AVIF, CAP));

        byte[] saved = Files.readAllBytes(file.toPath());
        assertEquals("a JPEG's start of image", 0xFF, saved[0] & 0xFF);
        assertEquals(0xD8, saved[1] & 0xFF);
        Bitmap back = BitmapFactory.decodeFile(file.getPath());
        assertNotNull("the JPEG decodes", back);
        assertEquals(48, back.getWidth());
        assertEquals(64, back.getHeight());
        int red = Color.red(back.getPixel(24, 32));
        assertTrue("the picture survives, red " + red, red > 180 && Color.green(back.getPixel(24, 32)) < 70);
        assertFalse("no work file is left beside it", new File(file.getPath() + ".jpg").exists());
    }

    @Test
    public void aConversionThatFailsLeavesTheAvifAsItCame() throws IOException {
        File file = avif();
        PhotoFormat.decoderForTests = source -> {
            throw new IOException("unreadable");
        };
        assertEquals(PhotoFormat.AVIF, PhotoFormat.jpegFromAvif(file, PhotoFormat.AVIF, CAP));
        assertArrayEquals(AVIF_HEAD, Files.readAllBytes(file.toPath()));
        assertFalse(new File(file.getPath() + ".jpg").exists());

        PhotoFormat.decoderForTests = source -> {
            throw new OutOfMemoryError("a huge photo");
        };
        assertEquals("running out of memory keeps the AVIF too", PhotoFormat.AVIF, PhotoFormat.jpegFromAvif(file, PhotoFormat.AVIF, CAP));
        assertArrayEquals(AVIF_HEAD, Files.readAllBytes(file.toPath()));
    }

    @Test
    public void otherTypesAreLeftAlone() throws IOException {
        File file = avif();
        PhotoFormat.decoderForTests = source -> {
            fail("only an AVIF is decoded");
            return null;
        };
        for (String mime : new String[] {"image/jpeg", "image/png", "image/webp", "image/heic", "video/mp4", null}) {
            assertEquals(mime, PhotoFormat.jpegFromAvif(file, mime, CAP));
        }
        assertArrayEquals(AVIF_HEAD, Files.readAllBytes(file.toPath()));
    }

    @Test
    public void android12AndLaterReadAvif() {
        assertTrue("sdk 31 is Android 12", PhotoFormat.readsAvif());
        PhotoFormat.readsAvifForTests = false;
        assertFalse(PhotoFormat.readsAvif());
    }
}
