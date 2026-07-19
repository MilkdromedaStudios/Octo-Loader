package studios.milkdromeda.octoloader.translate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studios.milkdromeda.octoloader.TestJars;
import studios.milkdromeda.octoloader.bootstrap.ModInjector;
import studios.milkdromeda.octoloader.knowledge.KnowledgeBase;
import studios.milkdromeda.octoloader.report.CompatReport;
import studios.milkdromeda.octoloader.report.CompatStatus;
import studios.milkdromeda.octoloader.report.ModReportEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationPipelineTest {
    @TempDir
    Path gameDir;

    private CompatReport run() {
        TranslationContext context = TranslationContext.create(gameDir, "26.2", KnowledgeBase.load(),
                ModInjector.modsDir(gameDir.resolve("mods")), id -> false);
        return new TranslationPipeline().run(gameDir.resolve("mods"), context);
    }

    private CompatStatus statusOf(CompatReport report, String fileName) {
        for (ModReportEntry e : report.entries()) {
            if (e.mod().path().getFileName().toString().equals(fileName)) return e.status();
        }
        throw new AssertionError("no entry for " + fileName + " in " + report.entries());
    }

    @Test
    void routesEachEcosystemToTheRightVerdict() throws IOException {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);

        TestJars.jar(mods, "native-fabric.jar",
                Map.of("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"native\",\"version\":\"1.0\"}"));
        TestJars.jar(mods, "old-forge.jar", Map.of("META-INF/mods.toml", """
                modLoader="javafml"
                loaderVersion="[47,)"
                [[mods]]
                modId="oldforge"
                version="1.0"
                [[dependencies.oldforge]]
                modId="minecraft"
                versionRange="[1.20.1]"
                """));
        TestJars.jar(mods, "legacy.jar",
                Map.of("mcmod.info", "[{\"modid\":\"legacy\",\"version\":\"1.0\",\"mcversion\":\"1.12.2\"}]"));
        TestJars.jar(mods, "paper.jar", Map.of("paper-plugin.yml", """
                name: SomePlugin
                version: "1.0"
                main: org.example.Plugin
                api-version: "1.21"
                """));
        TestJars.jar(mods, "mystery.jar", Map.of("readme.txt", "hello"));

        CompatReport report = run();
        assertEquals(5, report.entries().size());
        assertEquals(CompatStatus.NATIVE, statusOf(report, "native-fabric.jar"));
        assertEquals(CompatStatus.UNSUPPORTED_VERSION, statusOf(report, "old-forge.jar"));
        assertEquals(CompatStatus.UNSUPPORTED_VERSION, statusOf(report, "legacy.jar"));
        assertEquals(CompatStatus.UNSUPPORTED_ECOSYSTEM, statusOf(report, "paper.jar"));
        assertEquals(CompatStatus.UNRECOGNIZED, statusOf(report, "mystery.jar"));
    }

    @Test
    void modernQuiltModIsTranslatedAndCachedOnRerun() throws IOException {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        TestJars.jar(mods, "quilt-modern.jar", Map.of("quilt.mod.json", """
                {
                  "schema_version": 1,
                  "quilt_loader": {
                    "id": "modernquilt",
                    "version": "1.0.0",
                    "entrypoints": {"init": "org.example.Mod"},
                    "depends": [{"id": "minecraft", "versions": "~26.2"}]
                  }
                }"""));

        CompatReport first = run();
        assertEquals(CompatStatus.TRANSLATED, statusOf(first, "quilt-modern.jar"));
        Path generated = mods.resolve("quilt-modern.octo.jar");
        assertTrue(Files.isRegularFile(generated), "translated jar should be in mods/");

        // Second run: cache hit, and the generated jar itself must not get a report row.
        CompatReport second = run();
        assertEquals(1, second.entries().size());
        ModReportEntry entry = second.entries().getFirst();
        assertEquals(CompatStatus.TRANSLATED, entry.status());
        assertTrue(entry.reason().contains("cache"), entry.reason());
    }

    @Test
    void qslDependentQuiltModIsFlaggedPartial() throws IOException {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        TestJars.jar(mods, "quilt-qsl.jar", Map.of("quilt.mod.json", """
                {
                  "schema_version": 1,
                  "quilt_loader": {
                    "id": "qslmod",
                    "version": "1.0.0",
                    "depends": [
                      {"id": "minecraft", "versions": "~26.2"},
                      {"id": "qsl_base", "versions": "*"}
                    ]
                  }
                }"""));

        CompatReport report = run();
        ModReportEntry entry = report.entries().getFirst();
        assertEquals(CompatStatus.PARTIAL, entry.status());
        assertTrue(entry.reason().contains("qsl_base"), entry.reason());
        assertFalse(Files.exists(mods.resolve("quilt-qsl.octo.jar")), "partial mods must not emit jars");
    }

    @Test
    void staleGeneratedJarIsRemovedWhenSourceDisappears() throws IOException {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        TestJars.jar(mods, "gone.jar", Map.of("quilt.mod.json", """
                {
                  "schema_version": 1,
                  "quilt_loader": {"id": "gonemod", "version": "1.0.0",
                    "depends": [{"id": "minecraft", "versions": "~26.2"}]}
                }"""));
        run();
        Path generated = mods.resolve("gone.octo.jar");
        assertTrue(Files.isRegularFile(generated));

        Files.delete(mods.resolve("gone.jar"));
        run();
        assertFalse(Files.exists(generated), "orphaned generated jar should be cleaned up");
    }
}
