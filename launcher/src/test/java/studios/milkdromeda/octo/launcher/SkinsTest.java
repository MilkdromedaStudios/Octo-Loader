package studios.milkdromeda.octo.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkinsTest {
    @TempDir
    Path folder;

    private static byte[] png(int width, int height, int argb) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void keepsSkinsOnceEachAndForgetsThem() throws IOException {
        SkinLibrary library = new SkinLibrary(folder);
        byte[] red = png(64, 64, 0xFFFF0000);
        SkinLibrary.Entry first = library.add("Red", red, false);
        SkinLibrary.Entry again = library.add("Red again", red, true);

        assertEquals(first.id(), again.id(), "the same image is the same skin");
        assertEquals(1, library.list().size());
        assertEquals("Red again", library.list().get(0).name());
        assertTrue(library.list().get(0).slim());
        assertNotNull(library.find(red));

        // The same pixels saved differently (as Minecraft's texture server does) are the same skin.
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(red));
        BufferedImage other = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        other.getGraphics().drawImage(decoded, 0, 0, null);
        ByteArrayOutputStream reencoded = new ByteArrayOutputStream();
        ImageIO.write(other, "png", reencoded);
        assertFalse(java.util.Arrays.equals(red, reencoded.toByteArray()), "a different file");
        assertNotNull(library.find(reencoded.toByteArray()));

        SkinLibrary.Entry legacy = library.add("Old", png(64, 32, 0xFF00FF00), false);
        assertEquals(2, new SkinLibrary(folder).list().size(), "kept on disk");
        library.remove(legacy);
        assertEquals(1, library.list().size());
        assertFalse(Files.exists(library.file(legacy)));
    }

    @Test
    void refusesWhatIsNotASkin() throws IOException {
        SkinLibrary library = new SkinLibrary(folder);
        IOException wrongSize = assertThrows(IOException.class, () -> library.add("Big", png(128, 128, -1), false));
        assertTrue(wrongSize.getMessage().contains("128×128"), wrongSize.getMessage());
        assertThrows(IOException.class, () -> library.add("Text", "not a png".getBytes(), false));
        assertTrue(library.list().isEmpty());
    }
}
