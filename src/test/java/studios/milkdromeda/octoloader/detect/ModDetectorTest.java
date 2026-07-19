package studios.milkdromeda.octoloader.detect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studios.milkdromeda.octoloader.TestJars;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModDetectorTest {
    private final ModDetector detector = new ModDetector();

    @TempDir
    Path dir;

    @Test
    void detectsFabricMod() throws IOException {
        Path jar = TestJars.jar(dir, "fabric.jar", Map.of("fabric.mod.json", """
                {
                  "schemaVersion": 1,
                  "id": "examplefabric",
                  "version": "1.2.3",
                  "name": "Example Fabric",
                  "depends": {"minecraft": "~26.2", "fabricloader": ">=0.19.0"},
                  "jars": [{"file": "META-INF/jars/lib.jar"}]
                }"""));
        DetectedMod mod = detector.detect(jar);
        assertEquals(ModEcosystem.FABRIC, mod.ecosystem());
        assertEquals("examplefabric", mod.modId());
        assertEquals("1.2.3", mod.version());
        assertEquals("~26.2", mod.targetMinecraft());
        assertEquals(">=0.19.0", mod.loaderVersion());
        assertEquals(java.util.List.of("META-INF/jars/lib.jar"), mod.nestedJars());
    }

    @Test
    void detectsQuiltMod() throws IOException {
        Path jar = TestJars.jar(dir, "quilt.jar", Map.of("quilt.mod.json", """
                {
                  "schema_version": 1,
                  "quilt_loader": {
                    "group": "org.example",
                    "id": "examplequilt",
                    "version": "2.0.0",
                    "metadata": {"name": "Example Quilt"},
                    "depends": [
                      {"id": "quilt_loader", "versions": ">=0.29.0"},
                      {"id": "minecraft", "versions": "~26.2"}
                    ]
                  }
                }"""));
        DetectedMod mod = detector.detect(jar);
        assertEquals(ModEcosystem.QUILT, mod.ecosystem());
        assertEquals("examplequilt", mod.modId());
        assertEquals("Example Quilt", mod.displayName());
        assertEquals("~26.2", mod.targetMinecraft());
        assertEquals(">=0.29.0", mod.loaderVersion());
    }

    @Test
    void detectsNeoForgeMod() throws IOException {
        Path jar = TestJars.jar(dir, "neoforge.jar", Map.of("META-INF/neoforge.mods.toml", """
                modLoader="javafml"
                loaderVersion="[4,)"
                license="MIT"

                [[mods]]
                modId="exampleneo"
                version="3.1.0"
                displayName="Example NeoForge"

                [[dependencies.exampleneo]]
                modId="neoforge"
                type="required"
                versionRange="[21.1.0,)"

                [[dependencies.exampleneo]]
                modId="minecraft"
                type="required"
                versionRange="[1.21.1,1.21.2)"
                """));
        DetectedMod mod = detector.detect(jar);
        assertEquals(ModEcosystem.NEOFORGE, mod.ecosystem());
        assertEquals("exampleneo", mod.modId());
        assertEquals("3.1.0", mod.version());
        assertEquals("[1.21.1,1.21.2)", mod.targetMinecraft());
    }

    @Test
    void detectsForgeModWithJarVersionPlaceholder() throws IOException {
        Path jar = TestJars.jar(dir, "forge.jar", Map.of(
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Version: 4.5.6\r\n\r\n",
                "META-INF/mods.toml", """
                        modLoader="javafml"
                        loaderVersion="[47,)"
                        license="MIT"

                        [[mods]]
                        modId="exampleforge"
                        version="${file.jarVersion}"
                        displayName="Example Forge"

                        [[dependencies.exampleforge]]
                        modId="minecraft"
                        versionRange="[1.20.1]"
                        """));
        DetectedMod mod = detector.detect(jar);
        assertEquals(ModEcosystem.FORGE, mod.ecosystem());
        assertEquals("exampleforge", mod.modId());
        assertEquals("4.5.6", mod.version());
        assertEquals("[1.20.1]", mod.targetMinecraft());
    }

    @Test
    void detectsLegacyForgeMod() throws IOException {
        Path jar = TestJars.jar(dir, "legacy.jar", Map.of("mcmod.info", """
                [{
                  "modid": "examplelegacy",
                  "name": "Example Legacy",
                  "version": "1.0",
                  "mcversion": "1.12.2"
                }]"""));
        DetectedMod mod = detector.detect(jar);
        assertEquals(ModEcosystem.FORGE_LEGACY, mod.ecosystem());
        assertEquals("examplelegacy", mod.modId());
        assertEquals("1.12.2", mod.targetMinecraft());
    }

    @Test
    void detectsPaperPlugin() throws IOException {
        Path jar = TestJars.jar(dir, "paperplugin.jar", Map.of("paper-plugin.yml", """
                name: ExamplePaper
                version: "7.0"
                main: org.example.Plugin
                api-version: "1.21"
                """));
        DetectedMod mod = detector.detect(jar);
        assertEquals(ModEcosystem.PAPER_PLUGIN, mod.ecosystem());
        assertEquals("ExamplePaper", mod.modId());
        assertEquals("7.0", mod.version());
        assertTrue(mod.targetMinecraft().contains("1.21"));
    }

    @Test
    void detectsBukkitPlugin() throws IOException {
        Path jar = TestJars.jar(dir, "plugin.jar", Map.of("plugin.yml", """
                name: ExampleBukkit
                version: 2.3
                main: org.example.Plugin
                """));
        DetectedMod mod = detector.detect(jar);
        assertEquals(ModEcosystem.BUKKIT_PLUGIN, mod.ecosystem());
        assertEquals("ExampleBukkit", mod.modId());
        assertEquals("2.3", mod.version());
    }

    @Test
    void multiLoaderJarPrefersFabricAndListsAllMarkers() throws IOException {
        Path jar = TestJars.jar(dir, "multi.jar", Map.of(
                "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"multi\",\"version\":\"1.0\"}",
                "META-INF/mods.toml", """
                        modLoader="javafml"
                        loaderVersion="[47,)"
                        [[mods]]
                        modId="multi"
                        version="1.0"
                        """));
        DetectedMod mod = detector.detect(jar);
        assertEquals(ModEcosystem.FABRIC, mod.ecosystem());
        assertTrue(mod.allMarkers().contains(ModEcosystem.FORGE));
        assertNotNull(mod.detail());
        assertTrue(mod.detail().contains("multi-loader"));
    }

    @Test
    void unknownJarDoesNotThrow() throws IOException {
        Path jar = TestJars.jar(dir, "plain.jar", Map.of("com/example/Foo.class", "not really a class"));
        DetectedMod mod = detector.detect(jar);
        assertEquals(ModEcosystem.UNKNOWN, mod.ecosystem());
    }

    @Test
    void malformedMetadataDegradesGracefully() throws IOException {
        Path jar = TestJars.jar(dir, "broken.jar", Map.of("fabric.mod.json", "{not json"));
        DetectedMod mod = detector.detect(jar);
        assertEquals(ModEcosystem.FABRIC, mod.ecosystem());
        assertNotNull(mod.detail());
        assertTrue(mod.detail().contains("malformed"));
    }
}
