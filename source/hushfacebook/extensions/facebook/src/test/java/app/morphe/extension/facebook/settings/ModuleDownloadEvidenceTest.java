/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * #168's module download evidence: with Debug logging on, the download screen's line says which of
 * the downloader's steps it took for that module and what the failed task held; with it off, or for
 * another module's steps, nothing is added. Google Play is never the downloader, and the line says
 * when Facebook wanted it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ModuleDownloadEvidenceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        ScreenLog.clearForTests();
    }

    @After
    public void restore() {
        BaseSettings.DEBUG.resetToDefault();
        ScreenLog.clearForTests();
    }

    /** Shaped like Facebook 583's download step: its text is all that's read. */
    static final class Action {
        final String modules;
        final String type;

        Action(String modules, String type) {
            this.modules = modules;
            this.type = type;
        }

        @Override public String toString() { return "RequestAction{modules=[" + modules + "]_" + type + "}"; }
    }

    /** Shaped like 583's download result: code, split install error, cause and two flags. */
    @SuppressWarnings("unused")
    static final class Result {
        int A00 = -1;
        Integer A01;
        Throwable A02;
        boolean A03;
        boolean A04;
    }

    /** Shaped like 583's step result, which wraps a download result. */
    @SuppressWarnings("unused")
    static final class Wrapped {
        Result A00 = new Result();
        Exception A01;
        boolean A02 = true;
    }

    /** Shaped like 583's task: its own class holds the fields, a plain base class holds none. */
    static class Task {}

    @SuppressWarnings("unused")
    static final class FailedTask extends Task {
        Exception A00;
        Object A01;
        ArrayList<Object> A02 = new ArrayList<>();
        boolean A03 = true;
        final Object A04 = new Object();
    }

    private static List<String> instantGames() {
        return List.of("instantgames");
    }

    @Test
    public void offNothingIsKept() {
        ModuleDownloadEvidence.step(new Action("instantgames", "LOAD"));
        ModuleDownloadEvidence.failed(new FailedTask());
        assertEquals("", ModuleDownloadEvidence.forScreen(instantGames(), SystemClock.elapsedRealtime()));
    }

    @Test
    public void theScreensLineTakesItsModulesStepsAndTheFailedTaskOnce() {
        BaseSettings.DEBUG.save(true);
        ModuleDownloadEvidence.step(new Action("instantgames", "CHECK"));
        ModuleDownloadEvidence.step(new Action("mlkit_mrz", "DOWNLOAD"));
        ModuleDownloadEvidence.step(new Action("instantgames, instantgamesads", "LOAD"));
        FailedTask task = new FailedTask();
        task.A01 = new Result();
        ModuleDownloadEvidence.failed(task);
        long now = SystemClock.elapsedRealtime();
        assertEquals(", steps: RequestAction{modules=[instantgames]_CHECK} then RequestAction{modules=[instantgames, instantgamesads]_LOAD},"
                        + " task: no exception, result {A00=-1 A01=null A02=null A03=false A04=false}",
                ModuleDownloadEvidence.forScreen(instantGames(), now));
        assertEquals("taken once", "", ModuleDownloadEvidence.forScreen(instantGames(), now));
    }

    @Test
    public void aScreenNamingNoModuleGetsEveryRecentStep() {
        BaseSettings.DEBUG.save(true);
        ModuleDownloadEvidence.step(new Action("mlkit_mrz", "DOWNLOAD"));
        assertEquals(", steps: RequestAction{modules=[mlkit_mrz]_DOWNLOAD}",
                ModuleDownloadEvidence.forScreen(List.of(), SystemClock.elapsedRealtime()));
    }

    @Test
    public void anOldStepIsLeftOut() {
        BaseSettings.DEBUG.save(true);
        ModuleDownloadEvidence.step(new Action("instantgames", "LOAD"));
        assertEquals("", ModuleDownloadEvidence.forScreen(instantGames(),
                SystemClock.elapsedRealtime() + ModuleDownloadEvidence.WINDOW + 1));
    }

    @Test
    public void aTaskSaysItsExceptionOrThatItHeldNoResult() throws Exception {
        FailedTask task = new FailedTask();
        assertEquals("no exception, no result", ModuleDownloadEvidence.task(task));
        task.A00 = new IllegalStateException("task has not completed");
        assertEquals("exception java.lang.IllegalStateException: task has not completed, no result", ModuleDownloadEvidence.task(task));
    }

    @Test
    public void aWrappedResultIsWrittenOneLevelDown() throws Exception {
        FailedTask task = new FailedTask();
        task.A01 = new Wrapped();
        assertEquals("no exception, result {A00={A00=-1 A01=null A02=null A03=false A04=false} A01=null A02=true}",
                ModuleDownloadEvidence.task(task));
    }

    /** Shaped like the cause Facebook's own code or Play's splits put in a result: its fields say nothing. */
    @SuppressWarnings("unused")
    static final class SplitFailure extends Exception {
        final int A00 = 7;

        SplitFailure(String message) {
            super(message);
        }
    }

    @Test
    public void aResultsCauseIsWrittenAsItsMessage() throws Exception {
        FailedTask task = new FailedTask();
        Result result = new Result();
        result.A02 = new SplitFailure("split not installed");
        task.A01 = result;
        assertEquals("no exception, result {A00=-1 A01=null A02=" + SplitFailure.class.getName() + ": split not installed A03=false A04=false}",
                ModuleDownloadEvidence.task(task));
    }

    @Test
    public void aTaskKeepsItsResultsFlagsPastALongMessage() throws Exception {
        FailedTask task = new FailedTask();
        Result result = new Result();
        result.A02 = new SplitFailure(new String(new char[150]).replace('\0', 'x'));
        task.A01 = result;
        String text = ModuleDownloadEvidence.task(task);
        assertTrue(text, text.endsWith("A03=false A04=false}"));
    }

    @Test
    public void aTaskThatFailedLongBeforeTheScreenClosedIsLeftOut() {
        BaseSettings.DEBUG.save(true);
        ModuleDownloadEvidence.failed(new FailedTask());
        assertEquals("", ModuleDownloadEvidence.forScreen(instantGames(),
                SystemClock.elapsedRealtime() + ModuleDownloadEvidence.WINDOW + 1));
    }

    @Test
    public void aStepThatCantBeReadIsLeftOutWithoutThrowing() {
        BaseSettings.DEBUG.save(true);
        ModuleDownloadEvidence.step(new Object() {
            @Override public String toString() { throw new IllegalStateException("broken"); }
        });
        ModuleDownloadEvidence.step(null);
        ModuleDownloadEvidence.failed(null);
        assertEquals("", ModuleDownloadEvidence.forScreen(List.of(), SystemClock.elapsedRealtime()));
    }

    @Test
    public void aLongStepIsCut() {
        BaseSettings.DEBUG.save(true);
        String modules = "instantgames" + new String(new char[ModuleDownloadEvidence.TEXT_LIMIT]).replace('\0', 'x');
        ModuleDownloadEvidence.step(new Action(modules, "LOAD"));
        String line = ModuleDownloadEvidence.forScreen(instantGames(), SystemClock.elapsedRealtime());
        assertEquals(", steps: ".length() + ModuleDownloadEvidence.TEXT_LIMIT + 3, line.length());
        assertTrue(line, line.endsWith("..."));
    }

    @Test
    public void theDownloadScreensLineCarriesTheEvidence() throws Exception {
        BaseSettings.DEBUG.save(true);
        ModuleDownloadEvidence.step(new Action("ludo_club", "LOAD"));
        ModuleDownloadEvidence.failed(new FailedTask());
        assertEquals("module download closed for ludo_club, error: java.lang.IllegalStateException:"
                        + " VoltronDownloadManagerImpl: No downloader available. isAppLastInstalledByGP: true,"
                        + " steps: RequestAction{modules=[ludo_club]_LOAD}, task: no exception, no result",
                ScreenLog.moduleDownload(new ScreenLogTest.DownloadScreen()));
    }

    @Test
    public void googlePlayIsNeverTheDownloaderAndTheLineSaysWhenItWasWanted() {
        assertFalse(ModuleDownloads.fromPlay(true));
        assertEquals("off, nothing kept", "", ModuleDownloadEvidence.forScreen(instantGames(), SystemClock.elapsedRealtime()));
        BaseSettings.DEBUG.save(true);
        assertFalse(ModuleDownloads.fromPlay(false));
        assertEquals("not wanted, nothing said", "", ModuleDownloadEvidence.forScreen(instantGames(), SystemClock.elapsedRealtime()));
        assertFalse(ModuleDownloads.fromPlay(true));
        ModuleDownloadEvidence.step(new Action("instantgames", "LOAD"));
        long now = SystemClock.elapsedRealtime();
        assertEquals(", Google Play skipped, steps: RequestAction{modules=[instantgames]_LOAD}",
                ModuleDownloadEvidence.forScreen(instantGames(), now));
        assertEquals("taken once", "", ModuleDownloadEvidence.forScreen(instantGames(), now));
        assertFalse(ModuleDownloads.fromPlay(true));
        assertEquals("an old skip is left out", "", ModuleDownloadEvidence.forScreen(instantGames(),
                SystemClock.elapsedRealtime() + ModuleDownloadEvidence.WINDOW + 1));
    }
}