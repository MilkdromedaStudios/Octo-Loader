package studios.milkdromeda.octo.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studios.milkdromeda.octo.mod.ModCandidate;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.testkit.JarBuilder;

/**
 * A jar for several loaders at once, read as the loader the folder is for.
 *
 * <p>Collective ships one jar with a {@code fabric.mod.json}, a
 * {@code mods.toml} and a {@code neoforge.mods.toml}. Read as Fabric in a
 * Forge folder, its Fabric half ran against a Fabric API nothing had
 * installed.
 */
class MultiLoaderJarTest {
    @TempDir
    Path gameDir;

    private Path modsDir;

    @BeforeEach
    void setUp() throws IOException {
        modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
    }

    private static String fabric(String id) {
        return "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\"}";
    }

    private static String forge(String id) {
        return """
                modLoader="javafml"
                loaderVersion="[1,)"
                license="MIT"
                [[mods]]
                modId="%s"
                version="1.0.0"
                """.formatted(id);
    }

    private void multiLoaderJar() throws IOException {
        new JarBuilder()
                .file("fabric.mod.json", fabric("collective"))
                .file("META-INF/mods.toml", forge("collective"))
                .file("META-INF/neoforge.mods.toml", forge("collective"))
                .writeTo(modsDir.resolve("collective.jar"));
    }

    private ModFormat formatOf(String id) {
        List<ModCandidate> candidates = new ModDiscoverer(gameDir.resolve("nested")).discover(List.of(modsDir))
                .candidates();
        return candidates.stream().filter(candidate -> candidate.metadata().id().equals(id)).findFirst()
                .orElseThrow().metadata().format();
    }

    @Test
    @DisplayName("in a Forge folder, a multi-loader jar is read as a Forge mod")
    void forgeFolderReadsForge() throws IOException {
        multiLoaderJar();
        new JarBuilder().file("META-INF/mods.toml", forge("geckolib")).writeTo(modsDir.resolve("geckolib.jar"));
        new JarBuilder().file("META-INF/mods.toml", forge("terrablender")).writeTo(modsDir.resolve("tb.jar"));

        assertEquals(ModFormat.FORGE, formatOf("collective"));
    }

    @Test
    @DisplayName("in a NeoForge folder, it is read as a NeoForge mod")
    void neoForgeFolderReadsNeoForge() throws IOException {
        multiLoaderJar();
        new JarBuilder().file("META-INF/neoforge.mods.toml", forge("jei")).writeTo(modsDir.resolve("jei.jar"));

        assertEquals(ModFormat.NEOFORGE, formatOf("collective"));
    }

    @Test
    @DisplayName("in a Fabric folder, it is read as a Fabric mod")
    void fabricFolderReadsFabric() throws IOException {
        multiLoaderJar();
        new JarBuilder().file("fabric.mod.json", fabric("lithium")).writeTo(modsDir.resolve("lithium.jar"));

        assertEquals(ModFormat.FABRIC, formatOf("collective"));
    }
}
