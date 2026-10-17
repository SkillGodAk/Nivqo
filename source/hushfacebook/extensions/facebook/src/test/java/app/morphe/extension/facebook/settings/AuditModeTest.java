/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * The audit folder is what lets scripts/device-audit drive Hushfacebook over adb, and without
 * it every audit extra is ignored.
 */
@RunWith(RobolectricTestRunner.class)
public class AuditModeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;
    private File folder;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        folder = new File(context.getExternalFilesDir(null), AuditMode.FOLDER);
    }

    @After
    public void tearDown() {
        for (BooleanSetting setting : SettingsBackup.ALLOWLIST) setting.resetToDefault();
        File[] files = folder.listFiles();
        if (files != null) for (File file : files) file.delete();
        folder.delete();
    }

    @Test
    public void withoutTheFolderNothingIsReadOrWritten() throws IOException {
        File files = context.getExternalFilesDir(null);
        write(new File(files, "settings.json"), "{\"format\":\"hushfacebook-audit\",\"profile\":\"all-on\"}");
        AuditMode.onApplicationCreate(context);
        assertFalse(Settings.HIDE_REELS_TAB.get());
        assertNull(AuditMode.page(context, pageIntent("News feed")));
        new File(files, "settings.json").delete();
    }

    @Test
    public void allOnTurnsOnEverySwitchButTheExcludedOnes() throws IOException {
        folder.mkdirs();
        write(new File(folder, "settings.json"),
                "{\"format\":\"hushfacebook-audit\",\"profile\":\"all-on\",\"off\":[\"" + Settings.FORCE_DARK_MODE.key + "\"]}");
        AuditMode.onApplicationCreate(context);

        for (BooleanSetting setting : SettingsBackup.ALLOWLIST) {
            boolean expected = !AuditMode.ALL_ON_EXCLUDED.contains(setting) && setting != Settings.FORCE_DARK_MODE;
            assertEquals(setting.key, expected, setting.get());
        }
        assertTrue(read("import.txt").startsWith("applied "));
        assertFalse(new File(folder, "settings.json").exists());
        assertTrue(new File(folder, "settings.done.json").exists());
        // What was there before the import, all defaults here, kept for the audit to put back.
        assertTrue(read("before.json").contains("\"" + Settings.HIDE_REELS_TAB.key + "\": false"));
    }

    @Test
    public void defaultsPutEverySwitchBackExceptTheOnesNamed() throws IOException {
        folder.mkdirs();
        Settings.HIDE_REELS_TAB.save(true);
        write(new File(folder, "settings.json"),
                "{\"format\":\"hushfacebook-audit\",\"profile\":\"defaults\",\"on\":[\"" + Settings.EVENTS_ONLY.key + "\"]}");
        AuditMode.onApplicationCreate(context);

        assertFalse(Settings.HIDE_REELS_TAB.get());
        assertTrue(Settings.EVENTS_ONLY.get());
    }

    @Test
    public void aBackupFileIsImportedAsTheScreenWould() throws Exception {
        folder.mkdirs();
        Settings.HIDE_REELS_TAB.save(true);
        String backup = SettingsBackup.create();
        Settings.HIDE_REELS_TAB.save(false);
        write(new File(folder, "settings.json"), backup);
        AuditMode.onApplicationCreate(context);
        assertTrue(Settings.HIDE_REELS_TAB.get());
    }

    @Test
    public void aDamagedFileIsRefusedAndSaidSo() throws IOException {
        folder.mkdirs();
        write(new File(folder, "settings.json"), "{\"format\":\"hushfacebook-audit\",\"profile\":\"most\"}");
        AuditMode.onApplicationCreate(context);
        assertTrue(read("import.txt"), read("import.txt").startsWith("refused: "));
    }

    @Test
    public void aPageIsTakenOnlyInItsShape() {
        folder.mkdirs();
        assertEquals("Pause, backup and diagnostics", AuditMode.page(context, pageIntent("Pause, backup and diagnostics")));
        assertEquals("Meta's other products", AuditMode.page(context, pageIntent("Meta's other products")));
        assertNull(AuditMode.page(context, pageIntent("../../etc")));
        assertNull(AuditMode.page(context, new Intent()));
    }

    @Test
    public void theReportLandsWhereAdbCanPullIt() throws IOException {
        folder.mkdirs();
        AuditMode.writeReport(folder);
        assertFalse(read("report.txt").isEmpty());
        assertFalse(new File(folder, "report.txt.part").exists());
    }

    private static Intent pageIntent(String page) {
        return new Intent().putExtra(AuditMode.EXTRA_PAGE, page);
    }

    private String read(String name) throws IOException {
        return new String(Files.readAllBytes(new File(folder, name).toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
