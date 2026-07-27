package studios.milkdromeda.octo.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OctoMainTest {
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("an explicit game directory always wins")
    void usesExplicitGameDirectory() {
        Path declared = tempDir.resolve("instance");
        String unrelatedClassPath = tempDir.resolve("other/versions/1.21.8/1.21.8.jar").toString();

        assertEquals(declared.toAbsolutePath().normalize(),
                OctoMain.gameDirectory(List.of("--gameDir", declared.toString()), unrelatedClassPath));
    }

    @Test
    @DisplayName("the Minecraft directory is recovered from the version jar for old profiles")
    void infersGameDirectoryFromVersionJar() {
        Path gameDir = tempDir.resolve("minecraft");
        String classPath = String.join(File.pathSeparator,
                tempDir.resolve("launcher/runtime.jar").toString(),
                gameDir.resolve("libraries/studios/milkdromeda/octo-loader/1.0/octo-loader-1.0.jar").toString(),
                gameDir.resolve("versions/1.21.8/1.21.8.jar").toString());

        assertEquals(gameDir.toAbsolutePath().normalize(), OctoMain.gameDirectory(List.of(), classPath));
    }

    @Test
    @DisplayName("an unexpanded launcher placeholder is recovered from the libraries path")
    void ignoresUnexpandedPlaceholder() {
        Path gameDir = tempDir.resolve("custom-instance");
        String classPath = gameDir.resolve("libraries/studios/milkdromeda/octo-loader.jar").toString();

        assertEquals(gameDir.toAbsolutePath().normalize(),
                OctoMain.gameDirectory(List.of("--gameDir", "${game_directory}"), classPath));
    }
}
