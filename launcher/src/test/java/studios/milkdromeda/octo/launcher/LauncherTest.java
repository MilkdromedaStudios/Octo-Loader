package studios.milkdromeda.octo.launcher;

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
    void aDevBuildIsNeverOfferedAnUpdate() {
        assertEquals("dev", Launcher.build());
        assertFalse(Updates.isNewer(new Updates.Release("build-abc", "x", "u", "l", "n", Map.of())));
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
