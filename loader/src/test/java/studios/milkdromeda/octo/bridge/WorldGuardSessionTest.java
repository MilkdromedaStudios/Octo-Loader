package studios.milkdromeda.octo.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldGuardSessionTest {
    @TempDir
    Path game;

    @Test
    void aCrashReportWrittenDuringTheSessionMeansItDidNotCloseCleanly() throws Exception {
        Path world = Files.createDirectories(game.resolve("saves/World"));
        Path reports = Files.createDirectories(game.resolve("crash-reports"));
        Instant opened = Instant.now().minusSeconds(60);

        Path old = Files.writeString(reports.resolve("crash-old.txt"), "old");
        Files.setLastModifiedTime(old, FileTime.from(opened.minusSeconds(3600)));
        assertFalse(WorldGuard.crashedSince(world, opened), "a crash from before the session is not this one's");

        Files.writeString(reports.resolve("crash-new.txt"), "new");
        assertTrue(WorldGuard.crashedSince(world, opened));
    }

    @Test
    void aServersCrashReportsSitBesideItsWorld() throws Exception {
        Path world = Files.createDirectories(game.resolve("server/world"));
        Files.writeString(Files.createDirectories(game.resolve("server/crash-reports")).resolve("crash.txt"), "x");

        assertTrue(WorldGuard.crashedSince(world, Instant.now().minusSeconds(60)));
    }
}
