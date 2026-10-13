/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.provider.MediaStore;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.WorkerPoolForTests;

/** The export row describes the operation until it really finishes, without retaining its data. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class ExportDiagnosticReportPreferenceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> activity;

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        resetRows();
        LogBufferManager.clearReportSectionsForTests();
        LogBufferManager.copiedMessage = null;
        LogBufferManager.copyFailedMessage = null;
        LogBufferManager.exportFailedMessage = null;
        LogBufferManager.couldNotStartMessage = null;
        LogBufferManager.alreadySavingMessage = null;
        ShadowToast.reset();
        activity = Robolectric.buildActivity(Activity.class).setup();
    }

    @After public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        LogBufferManager.clearReportSectionsForTests();
        resetRows();
        activity.pause().stop().destroy();
    }

    /** Outcomes are process memory, so every test starts and ends with a fresh screen's state. */
    private static void resetRows() throws Exception {
        Field state = ExportDiagnosticReportPreference.class.getDeclaredField("state");
        state.setAccessible(true);
        state.set(null, state.getType().getEnumConstants()[0]);
        Field rows = ExportDiagnosticReportPreference.class.getDeclaredField("ROWS");
        rows.setAccessible(true);
        ((List<?>) rows.get(null)).clear();
    }

    private ExportDiagnosticReportPreference row() {
        ExportDiagnosticReportPreference row = new ExportDiagnosticReportPreference(activity.get());
        row.setSummary("Copy or save a diagnostic report.");
        return row;
    }

    private void choose(ExportDiagnosticReportPreference row, int which) {
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("the chooser didn't open immediately", dialog.isShowing());
        android.widget.ListView list = dialog.getListView();
        list.performItemClick(list.getAdapter().getView(which, null, list), which, which);
    }

    private static ClipboardManager clipboard() {
        return RuntimeEnvironment.getApplication().getSystemService(ClipboardManager.class);
    }

    @Test public void copyingDisablesNewAndExistingRowsUntilTheClipboardCommit() throws Exception {
        AtomicInteger built = new AtomicInteger();
        LogBufferManager.registerReportSection(new LogBufferManager.ReportSection() {
            @Override public String title() { return "PROBE"; }
            @Override public List<String> lines() {
                built.incrementAndGet();
                return Collections.singletonList("one report");
            }
        });
        clipboard().setPrimaryClip(ClipData.newPlainText("test", "before"));
        ExportDiagnosticReportPreference first = row();
        choose(first, 0);
        Utils.awaitBackgroundTasksForTests();

        assertFalse(first.isEnabled());
        assertEquals("Copying diagnostic report...", first.getSummary());
        assertEquals("before", clipboard().getPrimaryClip().getItemAt(0).getText());
        ExportDiagnosticReportPreference reopened = row();
        assertFalse(reopened.isEnabled());
        assertEquals(first.getSummary(), reopened.getSummary());
        LinearLayout bound = new LinearLayout(activity.get());
        reopened.onBindView(bound);
        assertEquals(reopened.getSummary(), bound.getStateDescription());

        List<Boolean> duplicate = new ArrayList<>();
        LogBufferManager.exportToFile(duplicate::add);
        assertEquals(Collections.singletonList(false), duplicate);
        assertEquals(1, built.get());

        ShadowLooper.idleMainLooper();
        assertTrue(first.isEnabled());
        assertTrue(reopened.isEnabled());
        assertEquals("Diagnostic report copied.", first.getSummary());
        assertEquals(first.getSummary(), reopened.getSummary());
        assertFalse(first.isPersistent());
        assertTrue(clipboard().getPrimaryClip().getItemAt(0).getText().toString()
                .startsWith("MORPHE DIAGNOSTIC REPORT\n"));
    }

    @Test public void savingEndsOnlyAfterPublicationAndNeverPutsTheDestinationInTheRow() throws Exception {
        LogBufferManagerExportTest.Downloads downloads = Robolectric.setupContentProvider(
                LogBufferManagerExportTest.Downloads.class, MediaStore.AUTHORITY);
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getContentResolver())
                .registerOutputStream(downloads.uriFor(1), new ByteArrayOutputStream());
        ExportDiagnosticReportPreference row = row();
        choose(row, 1);
        assertFalse(row.isEnabled());
        assertEquals("Saving diagnostic report...", row.getSummary());
        Utils.awaitBackgroundTasksForTests();
        assertEquals(Integer.valueOf(0), downloads.row(1).getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertFalse("the row finished before its main-thread result", row.isEnabled());

        ShadowLooper.idleMainLooper();
        assertTrue(row.isEnabled());
        assertEquals("Diagnostic report saved.", row.getSummary());
        assertFalse(row.getSummary().toString().contains(
                downloads.row(1).getAsString(MediaStore.MediaColumns.DISPLAY_NAME)));
    }

    @Test public void aFullWorkerQueueReturnsBothActionsToAnEnabledErrorRow() throws Exception {
        ExportDiagnosticReportPreference row = row();
        try (WorkerPoolForTests full = WorkerPoolForTests.fill()) {
            choose(row, 0);
            assertTrue(row.isEnabled());
            assertEquals("The report couldn't be copied. Try again.", row.getSummary());
            choose(row, 1);
            assertTrue(row.isEnabled());
            assertEquals("The report couldn't be saved. Try again.", row.getSummary());
        }

        choose(row, 0);
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertEquals("Diagnostic report copied.", row.getSummary());
    }

    @Test public void aFatalReportSectionReleasesOwnershipAndAllowsTheNextExport() throws Exception {
        LogBufferManager.registerReportSection(new LogBufferManager.ReportSection() {
            @Override public String title() { throw new AssertionError("report section failed"); }
            @Override public List<String> lines() { return Collections.singletonList("one report line"); }
        });
        clipboard().setPrimaryClip(ClipData.newPlainText("test", "before"));
        ExportDiagnosticReportPreference row = row();
        choose(row, 0);
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertTrue(row.isEnabled());
        assertEquals("The report couldn't be copied. Try again.", row.getSummary());
        assertEquals("before", clipboard().getPrimaryClip().getItemAt(0).getText());

        LogBufferManager.clearReportSectionsForTests();
        choose(row, 0);
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertTrue(row.isEnabled());
        assertEquals("Diagnostic report copied.", row.getSummary());
    }

    @Test public void aCopyFailureNamesCopyingAndKeepsPrivateExceptionTextOutOfTheRow() throws Exception {
        LogBufferManager.registerReportSection(new LogBufferManager.ReportSection() {
            @Override public String title() {
                throw new IllegalStateException("https://private.example/report?token=private-probe");
            }
            @Override public List<String> lines() { return Collections.singletonList("one report line"); }
        });
        clipboard().setPrimaryClip(ClipData.newPlainText("test", "before"));
        ExportDiagnosticReportPreference row = row();
        choose(row, 0);
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();

        assertTrue(row.isEnabled());
        assertEquals("The report couldn't be copied. Try again.", row.getSummary());
        assertEquals("The diagnostic report couldn't be copied. Try again.", ShadowToast.getTextOfLatestToast());
        assertEquals("before", clipboard().getPrimaryClip().getItemAt(0).getText());
        assertFalse(row.getSummary().toString().contains("private"));
    }
}
