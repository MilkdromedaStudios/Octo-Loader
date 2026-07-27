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
        Path logFile = directory.resolve(".octo/logs/octo-loader.log");
        OctoLog.openFile(logFile);
        OctoLog.of("Bootstrap").info("starting {}", "now");
        OctoLog.writeThrowable(new IllegalStateException("boom"));

        String contents = Files.readString(logFile);
        assertTrue(contents.contains("[Octo/Bootstrap/INFO] starting now"));
        assertTrue(contents.contains("IllegalStateException: boom"));
    }

    @Test
    void persistentLogIncludesMessagesHiddenByConsoleThreshold() throws Exception {
        Path logFile = directory.resolve(".octo/logs/octo-loader.log");
        OctoLog.openFile(logFile);
        OctoLog.setThreshold(OctoLog.Level.INFO);

        OctoLog.of("Bootstrap").trace("early trace detail");
        OctoLog.of("Bootstrap").debug("early debug detail");

        String contents = Files.readString(logFile);
        assertTrue(contents.contains("[Octo/Bootstrap/TRACE] early trace detail"));
        assertTrue(contents.contains("[Octo/Bootstrap/DEBUG] early debug detail"));
    }

    @Test
    void errorOverloadWritesThrowableToPersistentLog() throws Exception {
        Path logFile = directory.resolve(".octo/logs/octo-loader.log");
        OctoLog.openFile(logFile);

        OctoLog.of("Bootstrap").error("startup failed", new IllegalStateException("before Minecraft"));

        String contents = Files.readString(logFile);
        assertTrue(contents.contains("[Octo/Bootstrap/ERROR] startup failed"));
        assertTrue(contents.contains("IllegalStateException: before Minecraft"));
    }
}
