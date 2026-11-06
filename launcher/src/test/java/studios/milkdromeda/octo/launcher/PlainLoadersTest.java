package studios.milkdromeda.octo.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import studios.milkdromeda.octo.launcher.PlainLoaders.Loader;

class PlainLoadersTest {
    @Test
    void neoForgeIsNumberedAfterMinecraftEitherWay() {
        assertEquals("21.1.", PlainLoaders.neoForgePrefix("1.21.1"));
        assertEquals("21.0.", PlainLoaders.neoForgePrefix("1.21"));
        assertEquals("26.3.0.", PlainLoaders.neoForgePrefix("26.3"));
        assertEquals("26.1.2.", PlainLoaders.neoForgePrefix("26.1.2"));
    }

    @Test
    void theNewestNeoForgeReleaseIsChosenByItsNumbers() {
        String metadata = "<versions><version>26.3.0.99</version><version>26.3.0.101</version>"
                + "<version>26.3.0.100</version><version>26.3.0.102-beta</version><version>21.1.200</version>"
                + "</versions>";

        assertEquals("26.3.0.101", PlainLoaders.newestNeoForge(metadata, "26.3"));
        assertEquals("21.1.200", PlainLoaders.newestNeoForge(metadata, "1.21.1"));
        assertEquals("26.4.0.1-beta", PlainLoaders.newestNeoForge("<version>26.4.0.1-beta</version>", "26.4"),
                "a beta only when there is nothing else");
        assertNull(PlainLoaders.newestNeoForge(metadata, "26.5"));
    }

    @Test
    void fabricAndQuiltTakeTheNewestStableLoader() {
        assertEquals("0.20.1", PlainLoaders.newestStable(JsonParser.parseString(
                "[{\"loader\":{\"version\":\"0.21.0-beta.1\",\"stable\":false}},"
                        + "{\"loader\":{\"version\":\"0.20.1\",\"stable\":true}}]").getAsJsonArray()));
        // Quilt's list says nothing of stability: a version with no pre-release tag is stable.
        assertEquals("0.19.2", PlainLoaders.newestStable(JsonParser.parseString(
                "[{\"loader\":{\"version\":\"0.20.0-beta.9\"}},{\"loader\":{\"version\":\"0.19.2\"}}]")
                .getAsJsonArray()));
    }

    @Test
    void aPlainGamePlaysInAFolderOfItsOwn() {
        Path game = Path.of("/games/minecraft");

        assertEquals(game.resolve("instances/fabric-26.3"), PlainLoaders.instance(game, Loader.FABRIC, "26.3"));

        LauncherSettings settings = new LauncherSettings();
        settings.gameDirectory = game.toString();
        settings.selectedVersion = "26.3";
        assertEquals(game, settings.playDir(), "Octo plays in the game folder");
        settings.loader = "neoforge";
        assertEquals(game.resolve("instances/neoforge-26.3"), settings.playDir());
    }

    @Test
    void loadersAreNamedByTheirSetting() {
        assertEquals(Loader.QUILT, Loader.of("Quilt"));
        assertEquals(Loader.OCTO, Loader.of("something else"));
        assertFalse(Loader.OCTO.plain());
        assertTrue(Loader.VANILLA.plain());
        assertNull(Loader.VANILLA.modrinth());
        assertEquals("forge", Loader.FORGE.modrinth());
        assertEquals(List.of("octo", "vanilla", "fabric", "quilt", "forge", "neoforge"),
                java.util.Arrays.stream(Loader.values()).map(loader -> loader.key).toList());
    }
}
