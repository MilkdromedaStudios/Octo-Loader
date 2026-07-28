package studios.milkdromeda.octo.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The guards that keep the window out of the way of everything that is not a
 * player at a desk: servers, build machines, and this test suite.
 */
class ReportWindowTest {
    private static final Path LOG = Path.of("octo-loader.log");

    @Test
    @DisplayName("a clean launch opens nothing")
    void nothingToShow() {
        assertFalse(ReportWindow.show(new LoadingReport(), LOG));
    }

    @Test
    @DisplayName("-Docto.noReportWindow keeps a failing launch to the log")
    void switchedOff() {
        LoadingReport report = new LoadingReport();
        report.error("create", "it failed while starting up", "java.lang.NoSuchMethodError");

        // The build sets this for every test; a suite that opens windows is a
        // suite nobody runs, and a headless machine has nowhere to put one.
        assertTrue(Boolean.getBoolean("octo.noReportWindow"),
                "the build should be running tests with the window switched off");
        assertFalse(ReportWindow.show(report, LOG));
        assertFalse(ReportWindow.isOpen());
    }

    @Test
    @DisplayName("with no window open, waiting for one to close returns at once")
    void nothingToWaitFor() {
        assertTrue(ReportWindow.awaitClose(1, TimeUnit.MILLISECONDS));

        // And the calls the exit path makes are safe with nothing on screen.
        ReportWindow.gameStopped(true);
        ReportWindow.refresh();
        ReportWindow.close();
    }
}
