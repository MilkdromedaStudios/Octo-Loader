package studios.milkdromeda.octo.client.hud;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studios.milkdromeda.octo.client.Config;

/** Where the now-playing card goes, and sizes kept from older settings. */
class HudLayoutTest {
    @TempDir
    Path folder;

    @Test
    void theCardKeepsClearOfTheColumnDownTheRight() {
        // A wide 640×270 GUI, where the card fits beside the hotbar (which ends at 415), a 196×46 card.
        assertArrayEquals(new int[] { 438, 218 }, corner(640, 270, 640, 0), "nothing there: the corner");
        assertArrayEquals(new int[] { 438, 218 }, corner(640, 270, 500, 150), "the column ends above it");
        assertArrayEquals(new int[] { 438, 222 }, corner(640, 270, 560, 220),
                "the column reaches down to it, with room below: just below the column");
        assertArrayEquals(new int[] { 222, 6 }, corner(640, 270, 506, 240),
                "no room below: the top middle");
    }

    @Test
    void onANarrowScreenTheCardRisesAboveTheHotbar() {
        // 427 wide: the hotbar ends at 308, so the card (x 225) would cover it.
        assertArrayEquals(new int[] { 225, 178 }, corner(427, 270, 427, 0), "lifted above the hearts and food");
        assertArrayEquals(new int[] { 115, 6 }, corner(427, 270, 360, 200),
                "with the column in the way too: the top middle");
    }

    /** The corner for a 196×46 card, with Minecraft's hotbar at the bottom middle. */
    private static int[] corner(int width, int height, int columnLeft, int columnBottom) {
        return OctoHud.corner(width, height, 196, 46, columnLeft, columnBottom, width / 2 + 95, height - 42);
    }

    @Test
    void clocks() {
        assertEquals("0:05", OctoHud.clock(5_400));
        assertEquals("1:05", OctoHud.clock(65_000));
        assertEquals("1:02:05", OctoHud.clock(3_725_000));
    }

    @Test
    void theOldMinimapSizeCarriesOver() throws IOException {
        Path old = folder.resolve("old.json");
        Files.writeString(old, "{ \"minimapSize\": 2 }");
        assertEquals(128, Config.load(old).minimapPixels, "Large");

        Path fresh = folder.resolve("fresh.json");
        assertEquals(96, Config.load(fresh).minimapPixels, "a new player: Medium");
        assertEquals(100, Config.load(fresh).hudScale);

        Path wild = folder.resolve("wild.json");
        Files.writeString(wild, "{ \"minimapPixels\": 9000, \"hudScale\": 5, \"nowPlayingScale\": 999 }");
        Config config = Config.load(wild);
        assertEquals(256, config.minimapPixels);
        assertEquals(50, config.hudScale);
        assertEquals(200, config.nowPlayingScale);
    }
}
