package studios.milkdromeda.octoloader.translators.quilt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studios.milkdromeda.octoloader.TestJars;
import studios.milkdromeda.octoloader.bootstrap.ModInjector;
import studios.milkdromeda.octoloader.detect.ModDetector;
import studios.milkdromeda.octoloader.knowledge.KnowledgeBase;
import studios.milkdromeda.octoloader.report.CompatStatus;
import studios.milkdromeda.octoloader.report.ModReportEntry;
import studios.milkdromeda.octoloader.translate.TranslationCache;
import studios.milkdromeda.octoloader.translate.TranslationContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuiltTranslatorTest {
    private final QuiltTranslator translator = new QuiltTranslator();
    private final ModDetector detector = new ModDetector();

    @TempDir
    Path gameDir;

    @Test
    void rewritesMetadataCompletely() throws IOException {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        Path jar = TestJars.jar(mods, "sample.jar", Map.of(
                "quilt.mod.json", """
                        {
                          "schema_version": 1,
                          "quilt_loader": {
                            "group": "org.example",
                            "id": "sample",
                            "version": "2.5.0",
                            "metadata": {
                              "name": "Sample",
                              "description": "A sample.",
                              "contributors": {"Alex": "Owner"},
                              "license": "MIT"
                            },
                            "entrypoints": {
                              "init": "org.example.Init",
                              "client_init": ["org.example.ClientInit"]
                            },
                            "depends": [
                              {"id": "quilt_loader", "versions": "*"},
                              {"id": "minecraft", "versions": "~26.2"},
                              {"id": "some_lib", "versions": ">=3.0.0"},
                              {"id": "opt_lib", "versions": "*", "optional": true}
                            ],
                            "breaks": [{"id": "bad_mod", "versions": "*"}],
                            "provides": [{"id": "sample_api"}]
                          },
                          "mixin": "sample.mixins.json",
                          "minecraft": {"environment": "*"}
                        }""",
                "org/example/Init.class", "fake",
                "assets/sample/icon.png", "fake"));

        TranslationContext context = TranslationContext.create(gameDir, "26.2", KnowledgeBase.load(),
                ModInjector.modsDir(mods), id -> false);
        ModReportEntry entry = translator.translate(detector.detect(jar), context);
        assertEquals(CompatStatus.TRANSLATED, entry.status());

        Path output = mods.resolve("sample.octo.jar");
        assertTrue(Files.isRegularFile(output));

        JsonObject fabric = readFabricMetadata(output);
        assertEquals("sample", fabric.get("id").getAsString());
        assertEquals("2.5.0", fabric.get("version").getAsString());
        assertEquals("Sample", fabric.get("name").getAsString());
        assertEquals("MIT", fabric.get("license").getAsString());
        assertEquals("Alex", fabric.getAsJsonArray("authors").get(0).getAsString());

        JsonObject entrypoints = fabric.getAsJsonObject("entrypoints");
        assertEquals("org.example.Init", entrypoints.getAsJsonArray("main").get(0).getAsString());
        assertEquals("org.example.ClientInit", entrypoints.getAsJsonArray("client").get(0).getAsString());
        assertNull(entrypoints.get("init"), "quilt entrypoint keys must be renamed");

        assertEquals("sample.mixins.json", fabric.getAsJsonArray("mixins").get(0).getAsString());

        JsonObject depends = fabric.getAsJsonObject("depends");
        assertEquals("~26.2", depends.get("minecraft").getAsString());
        assertEquals(">=3.0.0", depends.get("some_lib").getAsString());
        assertNotNull(depends.get("fabricloader"));
        assertNull(depends.get("quilt_loader"), "quilt_loader dependency must be dropped");
        assertNull(depends.get("opt_lib"), "optional dependencies must be dropped");

        assertEquals("*", fabric.getAsJsonObject("breaks").get("bad_mod").getAsString());
        assertEquals("sample_api", fabric.getAsJsonArray("provides").get(0).getAsString());

        // Original content preserved, provenance recorded.
        try (JarFile jf = new JarFile(output.toFile())) {
            assertNotNull(jf.getJarEntry("org/example/Init.class"));
            assertNotNull(jf.getJarEntry("assets/sample/icon.png"));
            assertEquals("sample.jar",
                    jf.getManifest().getMainAttributes().getValue(TranslationCache.ATTR_TRANSLATED_FROM));
        }
        assertTrue(TranslationCache.isFresh(jar, output, "26.2"));
    }

    private static JsonObject readFabricMetadata(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile());
             InputStream in = jf.getInputStream(jf.getJarEntry("fabric.mod.json"))) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
