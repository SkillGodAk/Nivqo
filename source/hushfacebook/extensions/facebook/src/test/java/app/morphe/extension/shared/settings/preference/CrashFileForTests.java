/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.settings.preference;

import android.content.Context;
import android.provider.MediaStore;

import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import java.io.ByteArrayOutputStream;

/** The crash file's side of the paused hooks probe, for tests outside this package. */
public final class CrashFileForTests {
    private CrashFileForTests() {
    }

    /** Hands a fresh crash to the report, as the crash handler does, and says whether a file went to Downloads. */
    public static boolean aCrashWritesAFile() {
        Context context = RuntimeEnvironment.getApplication();
        CrashFileTest.Downloads downloads = Robolectric.setupContentProvider(CrashFileTest.Downloads.class, MediaStore.AUTHORITY);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(downloads.uriFor(1), new ByteArrayOutputStream());
        JavaCrashReport.resetForTests();
        JavaCrashReport.save(Thread.currentThread(), new IllegalStateException("probe"));
        return !downloads.ids().isEmpty();
    }
}
