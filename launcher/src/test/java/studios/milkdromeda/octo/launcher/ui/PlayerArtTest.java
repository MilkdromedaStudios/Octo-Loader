package studios.milkdromeda.octo.launcher.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class PlayerArtTest {
    /** A skin with each part its own colour: head red, body green, everything else blue. */
    private static BufferedImage skin(int height) {
        BufferedImage skin = new BufferedImage(64, height, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 32; x++) {
                skin.setRGB(x, y, 0xFFFF0000);
            }
        }

        for (int y = 16; y < 32; y++) {
            for (int x = 0; x < 56; x++) {
                skin.setRGB(x, y, x >= 16 && x < 40 ? 0xFF00FF00 : 0xFF0000FF);
            }
        }

        // The left arm and leg, below.
        for (int y = 48; y < height; y++) {
            for (int x = 16; x < 48; x++) {
                skin.setRGB(x, y, 0xFF0000FF);
            }
        }

        return skin;
    }

    private static boolean has(BufferedImage image, int rgb) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);

                // Any shade of the colour: faces are darkened by the way they face.
                if ((pixel >>> 24) > 0 && Integer.bitCount(pixel & 0xFFFFFF) > 0
                        && ((pixel & 0xFFFFFF) & ~rgb) == 0) {
                    return true;
                }
            }
        }

        return false;
    }

    /** Pixels of the yellow cape, in any shade: red and green alike, no blue. */
    private static int yellow(BufferedImage image) {
        int found = 0;

        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;

                if ((pixel >>> 24) > 0 && r > 60 && Math.abs(r - g) < 20 && b < 20) {
                    found++;
                }
            }
        }

        return found;
    }

    @Test
    void drawsThePlayerFromTheFrontAndTheBack() {
        BufferedImage front = PlayerArt.render(skin(64), false, null, 25, 200);
        BufferedImage back = PlayerArt.render(skin(64), true, null, 200, 200);

        assertEquals(200, front.getHeight());
        assertTrue(has(front, 0xFF0000) && has(front, 0x00FF00) && has(front, 0x0000FF));
        assertTrue(has(back, 0xFF0000));
    }

    @Test
    void anOldSkinGetsLeftLimbsMirroredFromTheRight() {
        BufferedImage modern = PlayerArt.modern(skin(32));
        assertEquals(64, modern.getHeight());
        // The left leg's front (from the right leg's) and the left arm's front (from the right arm's).
        assertEquals(0xFF0000FF, modern.getRGB(20, 52));
        assertEquals(0xFF0000FF, modern.getRGB(36, 52));
    }

    @Test
    void aCapeIsBehindFromTheFrontAndSeenFromTheBack() {
        BufferedImage cape = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                cape.setRGB(x, y, 0xFFFFFF00);
            }
        }

        int back = yellow(PlayerArt.render(skin(64), false, cape, 180, 200));
        int front = yellow(PlayerArt.render(skin(64), false, cape, 0, 200));
        assertTrue(back > 2000, "the cape covers the back: " + back);
        // From the front only its edges show, beside the legs, as in the game.
        assertTrue(front * 10 < back, "front " + front + ", back " + back);
    }
}
