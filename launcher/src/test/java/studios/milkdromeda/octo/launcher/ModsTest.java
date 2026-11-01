package studios.milkdromeda.octo.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;

import studios.milkdromeda.octo.launcher.mods.ModLibrary;
import studios.milkdromeda.octo.launcher.mods.Modrinth;

class ModsTest {
    @TempDir
    Path game;

    private static Modrinth.Version version(String id, String published, List<String> games, List<String> loaders) {
        return new Modrinth.Version(id, "p", id, id, games, loaders, published, "u", id + ".jar", null, 1, List.of());
    }

    @Test
    void prefersAVersionMadeForTheGameItIsFor() {
        List<Modrinth.Version> versions = List.of(
                version("old", "2024-01-01T00:00:00Z", List.of("1.20.1"), List.of("forge")),
                version("exact", "2025-01-01T00:00:00Z", List.of("26.3"), List.of("fabric")),
                version("newer-other", "2026-01-01T00:00:00Z", List.of("1.21.1"), List.of("neoforge")));

        assertEquals("exact", Modrinth.best(versions, "26.3").id());
    }

    @Test
    void otherwiseTheNewestForTheNewestMinecraftItSupports() {
        List<Modrinth.Version> versions = List.of(
                version("for-1.20.1", "2026-03-01T00:00:00Z", List.of("1.20.1"), List.of("forge")),
                version("for-1.21.1", "2025-06-01T00:00:00Z", List.of("1.21.1"), List.of("neoforge")),
                version("for-1.21.1-older", "2025-01-01T00:00:00Z", List.of("1.21.1"), List.of("fabric")));

        assertEquals("for-1.21.1", Modrinth.best(versions, "26.3").id(),
                "the closest Minecraft wins over the newest upload for an older one: Octo rebuilds it");
    }

    @Test
    void readsASearchPage() {
        String body = """
                {"hits":[{"project_id":"LNytGWDc","slug":"create","title":"Create","description":"Moving parts",
                "author":"simibubi","downloads":28586855,"icon_url":"https://cdn/x.webp","color":8703084,
                "categories":["decoration","forge","neoforge","technology"],"versions":["1.20.1","1.21.1"]}],
                "offset":0,"limit":1,"total_hits":3515}""";
        Modrinth.Page page = ModrinthAccess.parse(body);

        assertEquals(3515, page.total());
        Modrinth.Project create = page.projects().get(0);
        assertEquals(List.of("forge", "neoforge"), create.loaders());
        assertEquals(List.of("decoration", "technology"), create.categories());
        assertEquals("https://modrinth.com/mod/create", create.pageUrl());
    }

    private static void jar(Path file, Map<String, String> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    @Test
    void readsEachFormatsMetadataAndTurnsModsOnAndOff() throws IOException {
        Path mods = game.resolve("mods");
        Files.createDirectories(mods);
        jar(mods.resolve("fabric.jar"), Map.of("fabric.mod.json",
                "{\"id\":\"sodium\",\"name\":\"Sodium\",\"version\":\"0.6\",\"icon\":\"icon.png\"}", "icon.png", "x"));
        jar(mods.resolve("neo.jar"), Map.of("META-INF/neoforge.mods.toml", """
                modLoader="javafml"
                logoFile="logo.png"
                [[mods]]
                modId="create"
                version="${file.jarVersion}"
                displayName="Create"
                """, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nImplementation-Version: 6.0.10\n"));
        Files.writeString(mods.resolve("notes.txt"), "not a mod");

        ModLibrary library = new ModLibrary(game);
        List<ModLibrary.InstalledMod> found = library.list();

        assertEquals(2, found.size());
        ModLibrary.InstalledMod create = found.get(0);
        assertEquals("Create", create.name());
        assertEquals("6.0.10", create.version(), "a version from the build is read from the manifest");
        assertEquals("NeoForge", create.loader());
        ModLibrary.InstalledMod sodium = found.get(1);
        assertEquals("Fabric", sodium.loader());
        assertNotNull(sodium.icon());

        ModLibrary.InstalledMod off = library.setEnabled(sodium, false);
        assertFalse(off.enabled());
        assertTrue(Files.exists(mods.resolve("fabric.jar.disabled")));
        assertTrue(library.setEnabled(off, true).enabled());
        assertTrue(Files.exists(mods.resolve("fabric.jar")));
    }

    @Test
    void shortensABuildNamedAfterItsCommit() {
        assertEquals("Octo Loader build f018971",
                News.title("Octo Loader Build f01897113a324f85606d8c887b28eb43b9e0cad8"));
        assertEquals("Octo Loader 1.0.0", News.title("Octo Loader 1.0.0"));
    }

    /** The search page parser, which is package-private in the mods package. */
    static final class ModrinthAccess {
        static Modrinth.Page parse(String body) {
            try {
                java.lang.reflect.Method method = Modrinth.class.getDeclaredMethod("parsePage",
                        com.google.gson.JsonObject.class);
                method.setAccessible(true);
                return (Modrinth.Page) method.invoke(null, JsonParser.parseString(body).getAsJsonObject());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
