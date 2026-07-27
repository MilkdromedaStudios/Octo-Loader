package studios.milkdromeda.octo.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studios.milkdromeda.octo.testkit.JarBuilder;

/**
 * Discovery has to be able to answer "why is this jar not loading".
 *
 * <p>Each case here is a file that produced no mod. The point of the tests is
 * not that it produced no mod — that part always worked — but that the reason
 * survives long enough for someone to read it.
 */
class DiscoveryReportTest {
    @TempDir
    Path gameDir;

    private Path modsDir;

    @BeforeEach
    void setUp() throws IOException {
        modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
    }

    private Discovery discover() {
        return new ModDiscoverer(gameDir.resolve("nested")).discover(List.of(modsDir));
    }

    private Discovery.Inspection inspectionOf(Discovery discovery, String fileName) {
        return discovery.inspections().stream()
                .filter(inspection -> inspection.file().getFileName().toString().equals(fileName))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("a jar whose metadata will not parse is recorded as broken, not ignored")
    void brokenMetadataIsRecorded() throws IOException {
        new JarBuilder().file("fabric.mod.json", "{ nope")
                .writeTo(modsDir.resolve("corrupt.jar"));

        Discovery discovery = discover();
        Discovery.Inspection inspection = inspectionOf(discovery, "corrupt.jar");

        assertNotNull(inspection, "the file should have been inspected: " + discovery.inspections());
        assertEquals(Discovery.Verdict.BROKEN_METADATA, inspection.verdict());
        assertTrue(inspection.detail().contains("fabric.mod.json"),
                "the reason should name the file that failed: " + inspection.detail());
        assertEquals(1, discovery.failures().size());
    }

    @Test
    @DisplayName("a library jar is recorded as not a mod, which is not a failure")
    void aLibraryJarIsNotAFailure() throws IOException {
        new JarBuilder().file("org/example/library.txt", "not a mod")
                .writeTo(modsDir.resolve("library.jar"));

        Discovery discovery = discover();

        assertEquals(Discovery.Verdict.NOT_A_MOD, inspectionOf(discovery, "library.jar").verdict());
        assertTrue(discovery.failures().isEmpty(), "a plain library is nobody's problem");
        assertEquals(1, discovery.filesInspected());
    }

    @Test
    @DisplayName("a truncated download is recorded as unreadable rather than skipped in silence")
    void anUnreadableArchiveIsRecorded() throws IOException {
        Files.write(modsDir.resolve("truncated.jar"), "PK and then nothing".getBytes(StandardCharsets.UTF_8));

        Discovery discovery = discover();

        assertEquals(Discovery.Verdict.UNREADABLE, inspectionOf(discovery, "truncated.jar").verdict());
        assertEquals(1, discovery.failures().size());
    }

    @Test
    @DisplayName("one bad jar does not stop the rest of the folder from being inspected")
    void discoveryCoversTheWholeFolder() throws IOException {
        Files.write(modsDir.resolve("aaa-truncated.jar"), new byte[] { 1, 2, 3 });
        new JarBuilder().file("fabric.mod.json", """
                { "schemaVersion": 1, "id": "goodmod", "version": "1.0.0" }
                """).writeTo(modsDir.resolve("zzz-good.jar"));

        Discovery discovery = discover();

        assertEquals(1, discovery.candidates().size(), "the good mod should still have been found");
        assertEquals("goodmod", discovery.candidates().get(0).id());
        assertEquals(2, discovery.filesInspected());
    }

    @Test
    @DisplayName("an exploded NeoForge mod directory is recognised in a dev environment")
    void explodedNeoForgeDirectoriesAreFound() throws IOException {
        Path exploded = modsDir.resolve("exploded/META-INF");
        Files.createDirectories(exploded);
        Files.writeString(exploded.resolve("neoforge.mods.toml"), """
                modLoader="javafml"
                loaderVersion="[21,)"
                license="MIT"
                [[mods]]
                modId="explodedmod"
                version="1.0.0"
                """);

        Discovery discovery = discover();

        assertEquals(1, discovery.candidates().size(),
                "the directory should have been inspected: " + discovery.describe());
        assertEquals("explodedmod", discovery.candidates().get(0).id());
    }

    @Test
    @DisplayName("the report names the folders that were scanned even when they hold nothing")
    void theReportAlwaysNamesTheFolders() {
        Discovery discovery = discover();

        assertTrue(discovery.describe().get(0).contains(modsDir.toString()),
                "the scanned folder should be in the report: " + discovery.describe());
        assertEquals(0, discovery.filesInspected());
    }
}
