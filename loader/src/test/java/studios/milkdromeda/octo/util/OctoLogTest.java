package studios.milkdromeda.octo.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OctoLogTest {
    @TempDir
    Path directory;

    @Test
    void writesPersistentBootstrapLogAndFailure() throws Exception {
        Path logFile = directory.resolve("logs/octo-loader.log");
        OctoLog.openFile(logFile);
        OctoLog.of("Bootstrap").info("starting {}", "now");
        OctoLog.writeThrowable(new IllegalStateException("boom"));

        String contents = Files.readString(logFile);
        assertTrue(contents.contains("[Octo/Bootstrap/INFO] starting now"));
        assertTrue(contents.contains("IllegalStateException: boom"));
    }
}
