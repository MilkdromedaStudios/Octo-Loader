package studios.milkdromeda.octo.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class LauncherTest {
    @TempDir
    Path game;

    @Test
    void readsChecksumsAsSha256sumWritesThem() {
        String a = "a".repeat(64);
        String b = "B".repeat(64);
        Map<String, String> sums = Updates.parseChecksums(a + "  octo-launcher.jar\n" + b + " *installer.jar\n"
                + "not a line\n");

        assertEquals(a, sums.get("octo-launcher.jar"));
        assertEquals("b".repeat(64), sums.get("installer.jar"));
        assertEquals(2, sums.size());
    }

    @Test
    void releasesAreComparedByTheirNumbers() {
        assertTrue(Updates.newer("v1.0.72", "v1.0.73"));
        assertTrue(Updates.newer("v1.0.99", "v1.0.100"), "as numbers, not as text");
        assertTrue(Updates.newer("v1.0.140", "v1.1.0"));
        assertFalse(Updates.newer("v1.0.73", "v1.0.73"), "the same release");
        assertFalse(Updates.newer("v1.0.73", "v1.0.72"), "never back to an older one");
        assertFalse(Updates.newer("v1.1.0", "v1.0.140"));
        assertTrue(Updates.newer("1.0.72", "v1.0.73"), "with or without the v");

        // Builds from before the numbering move up to the first numbered release, and no further back.
        assertTrue(Updates.newer("build-0123456789abcdef", "v1.0.72"));
        assertFalse(Updates.newer("v1.0.72", "build-0123456789abcdef"));
        assertTrue(Updates.newer("build-aaaa", "build-bbbb"), "between two of those, a different one, as before");
        assertFalse(Updates.newer("build-aaaa", "build-aaaa"));

        // Nothing downloaded yet: anything is newer; nothing offered: nothing is.
        assertTrue(Updates.newer(null, "v1.0.72"));
        assertFalse(Updates.newer("v1.0.72", null));
        assertArrayEquals(new int[] { 1, 0, 72 }, Updates.version("v1.0.72"));
        assertNull(Updates.version("build-abc"));
    }

    @Test
    void aDevBuildIsNeverOfferedAnUpdate() {
        assertEquals("dev", Launcher.build());
        assertFalse(Updates.isNewer(new Updates.Release("build-abc", "x", "u", "l", "n", Map.of())));
        assertFalse(Updates.isNewer(new Updates.Release("v9.9.9", "x", "u", "l", "n", Map.of())));
        // The release's number where the build was given one (a release build
        // on CI sets OCTO_VERSION), the project's own version otherwise.
        String number = System.getenv("OCTO_VERSION");
        assertEquals(number != null && !number.isBlank() ? number
                : studios.milkdromeda.octo.installer.Installer.VERSION, Launcher.version());
    }

    @Test
    void themesAreChosenByTheirSettingsNameAndRepaintTheWholePalette() {
        assertEquals(studios.milkdromeda.octo.launcher.ui.Style.Look.DARK,
                studios.milkdromeda.octo.launcher.ui.Style.Look.of("dark"));
        assertEquals(studios.milkdromeda.octo.launcher.ui.Style.Look.OCTO,
                studios.milkdromeda.octo.launcher.ui.Style.Look.of("something else"), "unknown: the original look");

        try {
            studios.milkdromeda.octo.launcher.ui.Style.apply(studios.milkdromeda.octo.launcher.ui.Style.Look.LIGHT);
            assertEquals(java.awt.Color.BLACK, studios.milkdromeda.octo.launcher.ui.Style.INK, "dark ink on light");
            assertTrue(studios.milkdromeda.octo.launcher.ui.Style.TEXT.getRed() < 64, "dark text on light");

            studios.milkdromeda.octo.launcher.ui.Style.apply(studios.milkdromeda.octo.launcher.ui.Style.Look.DARK);
            assertTrue(studios.milkdromeda.octo.launcher.ui.Style.glass());
            assertTrue(studios.milkdromeda.octo.launcher.ui.Style.PANEL.getAlpha() < 255, "glass panels let through");
            // Mixing a glass panel with a colour keeps it glass.
            assertTrue(studios.milkdromeda.octo.launcher.ui.Style.mix(studios.milkdromeda.octo.launcher.ui.Style.PANEL,
                    studios.milkdromeda.octo.launcher.ui.Style.RED, 0.1f).getAlpha() < 128);
        } finally {
            studios.milkdromeda.octo.launcher.ui.Style.apply(studios.milkdromeda.octo.launcher.ui.Style.Look.OCTO);
        }
    }

    @Test
    void selectsAProfileByMarkingItUsedLast() throws IOException {
        Files.writeString(game.resolve("launcher_profiles.json"),
                "{\"profiles\":{\"octo-loader-26.3\":{\"name\":\"Octo Loader 26.3\",\"javaArgs\":\"-Xmx6G\"}}}");

        OfficialLauncher.select(game, "octo-loader-26.3");

        JsonObject profile = JsonParser.parseString(Files.readString(game.resolve("launcher_profiles.json")))
                .getAsJsonObject().getAsJsonObject("profiles").getAsJsonObject("octo-loader-26.3");
        assertTrue(profile.has("lastUsed"));
        assertEquals("-Xmx6G", profile.get("javaArgs").getAsString(), "the player's settings are kept");
    }

    @Test
    void playNeedsTheOfficialLauncherToHaveRunFirst() {
        LauncherSettings settings = new LauncherSettings();
        settings.gameDirectory = game.toString();
        IOException refused = assertThrows(IOException.class, () -> Play.prepareAndOpen(settings, "26.3", m -> { }));
        assertTrue(refused.getMessage().contains("has not been run"));
    }

    @Test
    void worldsWithoutAGuardReportNeedNothing() throws IOException {
        Path world = game.resolve("saves/w");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "x");
        Worlds.World described = Worlds.describe(world);

        assertFalse(described.needsAttention());
        assertNull(Worlds.lastPlayed(game.resolve("elsewhere")));
    }
}
