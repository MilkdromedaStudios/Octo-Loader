package studios.milkdromeda.octo.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading Minecraft's own account of why it stopped. */
class GameWatchTest {
    @TempDir
    Path gameDir;

    private static final String CRASH_REPORT = """
            ---- Minecraft Crash Report ----
            // Why is it breaking :(

            Time: 2026-07-28 21:14:02
            Description: Initializing game

            java.lang.NullPointerException: Cannot invoke "net.minecraft.core.Holder$Reference.value()" \
            because "this.defaultValue" is null
            \tat net.minecraft.core.DefaultedMappedRegistry.byId(DefaultedMappedRegistry.java:41)
            \tat net.minecraft.client.Minecraft.<init>(Minecraft.java:498)

            A detailed walkthrough of the error, its code path and all known details is as follows:
            ---------------------------------------------------------------------------

            -- Head --
            Thread: Render thread
            Stacktrace:
            \tat net.minecraft.core.DefaultedMappedRegistry.byId(DefaultedMappedRegistry.java:41)

            -- System Details --
            Minecraft Version: 1.21.4
            """;

    @AfterEach
    void forget() {
        GameWatch.reset();
    }

    @Test
    @DisplayName("the phase and the exception come out; the machine dump stays behind")
    void parsingACrashReport() throws IOException {
        Path file = gameDir.resolve("crash-2026-07-28_21.14.02-client.txt");
        Files.writeString(file, CRASH_REPORT);

        LoadingReport.Crash crash = GameWatch.parse(file);

        assertEquals("Initializing game", crash.phase());
        assertTrue(crash.detail().startsWith("java.lang.NullPointerException"), crash.detail());
        assertTrue(crash.detail().contains("DefaultedMappedRegistry.byId"), crash.detail());
        assertFalse(crash.detail().contains("System Details"),
                "the environment dump belongs in a bug report, not in the window: " + crash.detail());
        assertFalse(crash.detail().contains("Why is it breaking"),
                "and neither does the joke: " + crash.detail());
        assertEquals(file, crash.file());
    }

    @Test
    @DisplayName("only a crash report from this launch counts")
    void yesterdaysCrashIsNotEvidence() throws IOException {
        Path reports = Files.createDirectories(gameDir.resolve("crash-reports"));
        Path old = reports.resolve("crash-2019-01-01_00.00.00-client.txt");
        Files.writeString(old, CRASH_REPORT);
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 86_400_000));

        GameWatch.armForTest(new LoadingReport(), gameDir, System.currentTimeMillis());

        assertTrue(GameWatch.readCrashReport().isEmpty(),
                "a crash report older than the launch is somebody else's crash");

        Path fresh = reports.resolve("crash-2026-07-28_21.14.02-client.txt");
        Files.writeString(fresh, CRASH_REPORT);

        Optional<LoadingReport.Crash> found = GameWatch.readCrashReport();

        assertTrue(found.isPresent());
        assertEquals("Initializing game", found.get().phase());
        assertEquals(fresh, found.get().file());
    }

    @Test
    @DisplayName("no crash-reports folder is not a crash")
    void nothingToFind() {
        GameWatch.armForTest(new LoadingReport(), gameDir, System.currentTimeMillis());

        assertTrue(GameWatch.readCrashReport().isEmpty());
    }

    @Test
    @DisplayName("a report with no description still yields the failure")
    void aReportWithoutADescription() throws IOException {
        Path file = gameDir.resolve("crash-old.txt");
        Files.writeString(file, """
                ---- Minecraft Crash Report ----

                java.lang.OutOfMemoryError: Java heap space
                \tat net.minecraft.client.Minecraft.run(Minecraft.java:1)
                """);

        LoadingReport.Crash crash = GameWatch.parse(file);

        assertEquals("", crash.phase());
        assertTrue(crash.detail().contains("OutOfMemoryError"), crash.detail());
        assertTrue(CrashReading.of(crash.detail()).meaning().contains("out of memory"),
                "and it should still be readable in words");
    }
}
