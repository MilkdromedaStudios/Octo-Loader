package studios.milkdromeda.octo.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the player is shown when a launch had problems. */
class LoadingReportTest {
    @Test
    @DisplayName("an empty report says so, and is not worth showing")
    void nothingToReport() {
        LoadingReport report = new LoadingReport();

        assertTrue(report.isEmpty());
        assertFalse(report.hasErrors());
        assertEquals("Every mod loaded", report.headline());
        assertFalse(ReportWindow.show(report, Path.of("octo-loader.log")),
                "a clean launch should not open a window");
    }

    @Test
    @DisplayName("the headline counts what is broken and what merely needed adjusting")
    void theHeadlineSeparatesErrorsFromWarnings() {
        LoadingReport report = new LoadingReport();

        report.warning("ancientmod", "built for 1.7.10, translated to run here", "");
        assertEquals("1 mod needed adjusting to run here", report.headline());
        assertFalse(report.hasErrors(), "a translated mod is running, so it is not an error");

        report.error("create", "it failed while starting up, so it is not running", "NoSuchMethodError");
        assertEquals("1 mod is not running, and 1 needed adjusting", report.headline());
        assertTrue(report.hasErrors());
    }

    @Test
    @DisplayName("the rendered report names every mod and keeps its detail")
    void theReportKeepsTheEvidence() {
        LoadingReport report = new LoadingReport();
        report.error("create", "it failed while starting up", "java.lang.NoSuchMethodError: getModFileById\nat Mods.<init>");
        report.warning("sodium", "one of its mixins did not apply", "target not found");

        String text = report.render();

        assertTrue(text.contains("Not running"), text);
        assertTrue(text.contains("Loaded with changes"), text);
        assertTrue(text.contains("create"), text);
        assertTrue(text.contains("getModFileById"), text);
        assertTrue(text.contains("at Mods.<init>"), "multi-line detail should survive: " + text);
        assertTrue(text.contains("sodium"), text);
    }

    @Test
    @DisplayName("a game that stopped leads the report, and is read back in words")
    void aCrashIsTheHeadline() {
        LoadingReport report = new LoadingReport();
        report.warning("ancientmod", "translated to run here", "");

        assertEquals("1 mod needed adjusting to run here", report.headline());

        report.crashed("Initializing game",
                "java.lang.NullPointerException: Cannot invoke \"net.minecraft.core.Holder$Reference.value()\" "
                        + "because \"this.defaultValue\" is null",
                Path.of("crash-reports/crash-2026-07-28_21.14.02-client.txt"));

        assertEquals("Minecraft stopped - Initializing game", report.headline(),
                "plain ASCII: this line heads the log and the report file");
        assertTrue(report.hasCrash());
        assertTrue(report.hasErrors(), "the game stopping is an error even when no mod failed");
        assertFalse(report.isEmpty(), "and it is worth showing on its own");

        String text = report.render();

        assertTrue(text.indexOf("What Minecraft was doing") < text.indexOf("Loaded with changes"),
                "the crash comes before the mod notes: " + text);
        assertTrue(text.contains("crash-2026-07-28_21.14.02-client.txt"), text);
        assertTrue(text.contains("What that usually means"), text);
        assertTrue(text.contains("default entry"), "the plain-language reading should be in the file: " + text);
        assertTrue(text.contains("this.defaultValue"), "and so should the evidence: " + text);
    }

    @Test
    @DisplayName("a crash with nothing else in the report is still a report")
    void aCrashOnItsOwn() {
        LoadingReport report = new LoadingReport();

        assertTrue(report.isEmpty());

        report.crashed("", "java.lang.OutOfMemoryError: Java heap space", null);

        assertFalse(report.isEmpty());
        assertEquals("Minecraft stopped", report.headline());
        assertTrue(report.render().contains("(not stated)"), report.render());
    }

    @Test
    @DisplayName("errors are listed before the things that merely changed")
    void errorsComeFirst() {
        LoadingReport report = new LoadingReport();
        report.warning("ancientmod", "translated", "");
        report.error("create", "not running", "");

        String text = report.render();

        assertTrue(text.indexOf("Not running") < text.indexOf("Loaded with changes"),
                "what is broken should be at the top: " + text);
    }
}
