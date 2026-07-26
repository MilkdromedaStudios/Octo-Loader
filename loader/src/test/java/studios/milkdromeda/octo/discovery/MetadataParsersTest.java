package studios.milkdromeda.octo.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studios.milkdromeda.octo.compat.Era;
import studios.milkdromeda.octo.mod.ModCandidate;
import studios.milkdromeda.octo.mod.ModDependency;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.testkit.JarBuilder;

/** Every metadata format Octo claims to read, read from a real jar. */
class MetadataParsersTest {
    @TempDir
    Path directory;

    private ModCandidate discover(Path jar) {
        List<ModCandidate> found = new ModDiscoverer(directory.resolve("nested")).discoverFile(jar, null);
        assertFalse(found.isEmpty(), "nothing was discovered in " + jar.getFileName());
        return found.get(0);
    }

    @Test
    @DisplayName("fabric.mod.json")
    void fabric() throws IOException {
        Path jar = new JarBuilder().file("fabric.mod.json", """
                {
                  "schemaVersion": 1,
                  "id": "examplemod",
                  "version": "1.4.2",
                  "name": "Example Mod",
                  "environment": "client",
                  "license": "MIT",
                  "authors": ["Ada", {"name": "Grace"}],
                  "entrypoints": {
                    "main": ["com.example.Main"],
                    "client": ["com.example.Client::init"]
                  },
                  "depends": { "minecraft": ">=1.20 <1.21", "fabricloader": ">=0.15" },
                  "breaks": { "oldmod": "*" },
                  "mixins": ["examplemod.mixins.json"],
                  "accessWidener": "examplemod.accesswidener",
                  "jars": [{ "file": "META-INF/jars/library.jar" }]
                }
                """).writeTo(directory.resolve("fabric.jar"));

        ModCandidate mod = discover(jar);

        assertEquals("examplemod", mod.id());
        assertEquals(ModFormat.FABRIC, mod.format());
        assertEquals("1.4.2", mod.metadata().version().raw());
        assertEquals(Side.CLIENT, mod.metadata().side());
        assertEquals(List.of("Ada", "Grace"), mod.metadata().authors());
        assertEquals("com.example.Main", mod.metadata().entrypoints("main").get(0).className());
        assertEquals("init", mod.metadata().entrypoints("client").get(0).memberName());
        assertEquals(List.of("examplemod.mixins.json"), mod.metadata().mixinConfigs());
        assertEquals(List.of("examplemod.accesswidener"), mod.metadata().accessWideners());
        assertEquals(List.of("META-INF/jars/library.jar"), mod.metadata().nestedJars());

        ModDependency minecraft = mod.metadata().minecraftDependency().orElseThrow();
        assertTrue(minecraft.versions().test("1.20.4"));
        assertFalse(minecraft.versions().test("1.21"));

        assertTrue(mod.metadata().dependencies().stream()
                .anyMatch(d -> d.modId().equals("oldmod") && d.kind() == ModDependency.Kind.BREAKS));
    }

    @Test
    @DisplayName("quilt.mod.json")
    void quilt() throws IOException {
        Path jar = new JarBuilder().file("quilt.mod.json", """
                {
                  "schema_version": 1,
                  "quilt_loader": {
                    "group": "com.example",
                    "id": "quiltmod",
                    "version": "2.0.0",
                    "metadata": {
                      "name": "Quilt Mod",
                      "description": "A mod",
                      "contributors": { "Ada": "Owner" },
                      "license": "Apache-2.0"
                    },
                    "entrypoints": { "init": ["com.example.QuiltMain"] },
                    "depends": [
                      { "id": "minecraft", "versions": ">=1.20" },
                      { "id": "quilt_base", "versions": "*", "optional": true },
                      "some_other_mod"
                    ],
                    "breaks": [{ "id": "conflicting", "versions": "*" }]
                  },
                  "minecraft": { "environment": "*" },
                  "mixin": "quiltmod.mixins.json"
                }
                """).writeTo(directory.resolve("quilt.jar"));

        ModCandidate mod = discover(jar);

        assertEquals("quiltmod", mod.id());
        assertEquals(ModFormat.QUILT, mod.format());
        assertEquals(Side.BOTH, mod.metadata().side());
        assertEquals(List.of("Ada"), mod.metadata().authors());
        assertEquals("com.example.QuiltMain", mod.metadata().entrypoints("init").get(0).className());
        assertEquals(List.of("quiltmod.mixins.json"), mod.metadata().mixinConfigs());

        Optional<ModDependency> optional = mod.metadata().dependencies().stream()
                .filter(d -> d.modId().equals("quilt_base")).findFirst();
        assertTrue(optional.isPresent());
        assertEquals(ModDependency.Kind.OPTIONAL, optional.get().kind());

        assertTrue(mod.metadata().dependencies().stream()
                .anyMatch(d -> d.modId().equals("some_other_mod") && d.kind() == ModDependency.Kind.REQUIRED));
    }

    @Test
    @DisplayName("META-INF/mods.toml, with mandatory booleans")
    void forge() throws IOException {
        Path jar = new JarBuilder()
                .file("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nImplementation-Version: 3.1.0\n\n")
                .file("META-INF/mods.toml", """
                        modLoader="javafml"
                        loaderVersion="[47,)"
                        license="LGPL-3.0"
                        [[mods]]
                        modId="forgemod"
                        version="${file.jarVersion}"
                        displayName="Forge Mod"
                        authors="Ada, Grace"
                        description='''A Forge mod.'''
                        [[dependencies.forgemod]]
                            modId="minecraft"
                            mandatory=true
                            versionRange="[1.20.1,1.21)"
                            ordering="NONE"
                            side="BOTH"
                        [[dependencies.forgemod]]
                            modId="jei"
                            mandatory=false
                            versionRange="[15,)"
                            ordering="AFTER"
                            side="BOTH"
                        [[mixins]]
                            config="forgemod.mixins.json"
                        """)
                .writeTo(directory.resolve("forge.jar"));

        ModCandidate mod = discover(jar);

        assertEquals("forgemod", mod.id());
        assertEquals(ModFormat.FORGE, mod.format());
        assertEquals("3.1.0", mod.metadata().version().raw(), "${file.jarVersion} should come from the manifest");
        assertEquals(List.of("Ada", "Grace"), mod.metadata().authors());
        assertEquals("LGPL-3.0", mod.metadata().license());
        assertEquals(List.of("forgemod.mixins.json"), mod.metadata().mixinConfigs());

        ModDependency jei = mod.metadata().dependencies().stream()
                .filter(d -> d.modId().equals("jei")).findFirst().orElseThrow();
        assertEquals(ModDependency.Kind.OPTIONAL, jei.kind());
        assertEquals(ModDependency.Ordering.AFTER, jei.ordering());

        ModDependency minecraft = mod.metadata().minecraftDependency().orElseThrow();
        assertEquals(ModDependency.Kind.REQUIRED, minecraft.kind());
        assertTrue(minecraft.versions().test("1.20.1"));
        assertEquals(Era.MODERN, mod.era(), "1.20.1 is the modern era");
    }

    @Test
    @DisplayName("META-INF/neoforge.mods.toml, with type strings")
    void neoForge() throws IOException {
        Path jar = new JarBuilder()
                .file("META-INF/neoforge.mods.toml", """
                        modLoader="javafml"
                        loaderVersion="[21,)"
                        license="MIT"
                        [[mods]]
                        modId="neomod"
                        version="1.0.0"
                        displayName="Neo Mod"
                        [[dependencies.neomod]]
                            modId="minecraft"
                            type="required"
                            versionRange="[1.21,1.22)"
                        [[dependencies.neomod]]
                            modId="badmod"
                            type="incompatible"
                            versionRange="*"
                        """)
                .writeTo(directory.resolve("neoforge.jar"));

        ModCandidate mod = discover(jar);

        assertEquals("neomod", mod.id());
        assertEquals(ModFormat.NEOFORGE, mod.format());
        assertEquals(ModDependency.Kind.BREAKS, mod.metadata().dependencies().stream()
                .filter(d -> d.modId().equals("badmod")).findFirst().orElseThrow().kind());
        assertEquals(Era.CURRENT, mod.era());
    }

    @Test
    @DisplayName("mcmod.info, the 1.7-through-1.12 format")
    void legacyForge() throws IOException {
        Path jar = new JarBuilder().file("mcmod.info", """
                [
                  {
                    "modid": "oldmod",
                    "name": "An Old Mod",
                    "description": "From another decade.",
                    "version": "1.2.3",
                    "mcversion": "1.7.10",
                    "authorList": ["Someone"],
                    "requiredMods": ["Forge@[10.13.4,)"],
                    "dependencies": ["required-after:CodeChickenCore@[1.0,)", "before:AnotherMod"]
                  }
                ]
                """).writeTo(directory.resolve("old.jar"));

        ModCandidate mod = discover(jar);

        assertEquals("oldmod", mod.id());
        assertEquals(ModFormat.LEGACY_FORGE, mod.format());
        assertEquals(Era.SEARGE, mod.era(), "1.7.10 is the Searge era");
        assertEquals(List.of("Someone"), mod.metadata().authors());
        assertEquals("1.7.10", mod.metadata().declaredMinecraftVersion().orElseThrow());

        ModDependency codeChicken = mod.metadata().dependencies().stream()
                .filter(d -> d.modId().equals("CodeChickenCore")).findFirst().orElseThrow();
        assertEquals(ModDependency.Kind.REQUIRED, codeChicken.kind(),
                "required-after: means required");
        assertEquals(ModDependency.Ordering.AFTER, codeChicken.ordering());

        ModDependency another = mod.metadata().dependencies().stream()
                .filter(d -> d.modId().equals("AnotherMod")).findFirst().orElseThrow();
        assertEquals(ModDependency.Ordering.BEFORE, another.ordering());
    }

    @Test
    @DisplayName("mcmod.info wrapped in a modList object")
    void legacyForgeModList() throws IOException {
        Path jar = new JarBuilder().file("mcmod.info", """
                { "modListVersion": 2, "modList": [ { "modid": "wrapped", "version": "0.1", "mcversion": "1.12.2" } ] }
                """).writeTo(directory.resolve("wrapped.jar"));

        ModCandidate mod = discover(jar);
        assertEquals("wrapped", mod.id());
        assertEquals(Era.SEARGE, mod.era());
    }

    @Test
    @DisplayName("litemod.json")
    void liteLoader() throws IOException {
        Path jar = new JarBuilder().file("litemod.json", """
                { "name": "VoxelMap", "displayName": "VoxelMap", "version": "1.9.28", "mcversion": "1.12.2",
                  "author": "MamiyaOtaru", "description": "A minimap." }
                """).writeTo(directory.resolve("map.litemod"));

        ModCandidate mod = discover(jar);

        assertEquals("voxelmap", mod.id());
        assertEquals(ModFormat.LITELOADER, mod.format());
        assertEquals(Side.CLIENT, mod.metadata().side(), "litemods were client-only");
        assertEquals(Era.SEARGE, mod.era());
    }

    @Test
    @DisplayName("a jar with no metadata at all is recovered from its bytecode")
    void bareJar() throws IOException {
        Map<String, byte[]> compiled = studios.milkdromeda.octo.testkit.SourceCompiler.compile(Map.of(
                "com.example.BareMod", """
                        package com.example;
                        public class BareMod implements net.fabricmc.api.ModInitializer {
                            public static boolean ran;
                            @Override public void onInitialize() { ran = true; }
                        }
                        """));

        Path jar = new JarBuilder().classes(compiled).writeTo(directory.resolve("bare.jar"));
        ModCandidate mod = discover(jar);

        assertEquals(ModFormat.BARE, mod.format());
        assertEquals("bare", mod.id(), "the file name becomes the id when nothing else is available");
        assertNotNull(mod.metadata().entrypoints("main"));
        assertEquals("com.example.BareMod", mod.metadata().entrypoints("main").get(0).className());
    }

    @Test
    @DisplayName("a Quilt mod shipping a fabric.mod.json too is read as Quilt, not twice")
    void quiltWinsOverFabric() throws IOException {
        Path jar = new JarBuilder()
                .file("quilt.mod.json", """
                        { "schema_version": 1, "quilt_loader": { "id": "dual", "version": "1.0.0",
                          "metadata": { "name": "Dual" } } }
                        """)
                .file("fabric.mod.json", """
                        { "schemaVersion": 1, "id": "dual", "version": "1.0.0" }
                        """)
                .writeTo(directory.resolve("dual.jar"));

        List<ModCandidate> found = new ModDiscoverer(directory.resolve("nested")).discoverFile(jar, null);

        assertEquals(1, found.size(), "one jar declaring itself twice is still one mod");
        assertEquals(ModFormat.QUILT, found.get(0).format());
    }

    @Test
    @DisplayName("nested jars are unpacked and loaded as mods in their own right")
    void nestedJars() throws IOException {
        Path library = new JarBuilder().file("fabric.mod.json", """
                { "schemaVersion": 1, "id": "nestedlib", "version": "0.9.0" }
                """).writeTo(directory.resolve("build/library.jar"));

        Path jar = new JarBuilder()
                .file("fabric.mod.json", """
                        { "schemaVersion": 1, "id": "outer", "version": "1.0.0",
                          "jars": [{ "file": "META-INF/jars/library.jar" }] }
                        """)
                .file("META-INF/jars/library.jar", java.nio.file.Files.readAllBytes(library))
                .writeTo(directory.resolve("outer.jar"));

        List<ModCandidate> found = new ModDiscoverer(directory.resolve("nested")).discoverFile(jar, null);

        assertEquals(2, found.size());
        assertEquals("outer", found.get(0).id());
        assertEquals("nestedlib", found.get(1).id());
        assertTrue(found.get(1).isNested());
        assertEquals("outer", found.get(1).parent().id());
    }

    @Test
    @DisplayName("Forge JarJar payloads are unpacked without being declared")
    void jarJar() throws IOException {
        Path library = new JarBuilder().file("META-INF/mods.toml", """
                modLoader="javafml"
                loaderVersion="[47,)"
                license="MIT"
                [[mods]]
                modId="jarjarlib"
                version="1.0.0"
                """).writeTo(directory.resolve("build/jarjarlib.jar"));

        Path jar = new JarBuilder()
                .file("META-INF/mods.toml", """
                        modLoader="javafml"
                        loaderVersion="[47,)"
                        license="MIT"
                        [[mods]]
                        modId="withjarjar"
                        version="1.0.0"
                        """)
                .file("META-INF/jarjar/jarjarlib.jar", java.nio.file.Files.readAllBytes(library))
                .writeTo(directory.resolve("withjarjar.jar"));

        List<ModCandidate> found = new ModDiscoverer(directory.resolve("nested")).discoverFile(jar, null);

        assertEquals(2, found.size());
        assertEquals("jarjarlib", found.get(1).id());
    }

    @Test
    @DisplayName("a broken metadata file is reported, not fatal")
    void brokenMetadataIsSkipped() throws IOException {
        Path jar = new JarBuilder()
                .file("fabric.mod.json", "{ this is not json")
                .writeTo(directory.resolve("broken.jar"));

        List<ModCandidate> found = new ModDiscoverer(directory.resolve("nested")).discoverFile(jar, null);
        assertTrue(found.isEmpty(), "a jar Octo cannot read is skipped, and the launch continues");
    }
}
