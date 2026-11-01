package studios.milkdromeda.octo.launcher.ui;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import studios.milkdromeda.octo.launcher.LauncherSettings;
import studios.milkdromeda.octo.launcher.auth.Account;
import studios.milkdromeda.octo.launcher.game.Net;

/**
 * A player's face, cut from their own skin: the 8×8 face with the hat layer
 * over it, scaled up without smoothing. The skin is kept in Octo's folder so
 * the face shows offline too.
 */
final class Heads {
    private static final Map<String, BufferedImage> SKINS = new ConcurrentHashMap<>();

    private Heads() {
    }

    /** The face at a size: a monogram now, the face when the skin has been read. */
    static void load(Account account, int size, int radius, Consumer<Image> ready) {
        ready.accept(Widgets.monogram(account.name(), Widgets.colourFor(account.name()), size, radius));
        Thread worker = new Thread(() -> {
            BufferedImage skin = skin(account);

            if (skin != null) {
                Image face = Widgets.rounded(face(skin, size), size, radius);
                SwingUtilities.invokeLater(() -> ready.accept(face));
            }
        }, "octo-head");
        worker.setDaemon(true);
        worker.start();
    }

    private static BufferedImage skin(Account account) {
        BufferedImage known = SKINS.get(account.id());

        if (known != null) {
            return known;
        }

        Path cache = LauncherSettings.home().resolve("heads").resolve(account.id() + ".png");
        BufferedImage skin = null;

        if (account.skinUrl() != null && account.skinUrl().startsWith("http")) {
            try {
                skin = ImageIO.read(new ByteArrayInputStream(fetch(account.skinUrl())));

                if (skin != null) {
                    Files.createDirectories(cache.getParent());
                    ImageIO.write(skin, "png", cache.toFile());
                }
            } catch (IOException | RuntimeException e) {
                skin = null;
            }
        }

        if (skin == null && Files.isRegularFile(cache)) {
            try {
                skin = ImageIO.read(cache.toFile());
            } catch (IOException e) {
                skin = null;
            }
        }

        if (skin != null && skin.getWidth() >= 64) {
            SKINS.put(account.id(), skin);
            return skin;
        }

        return null;
    }

    private static byte[] fetch(String url) throws IOException {
        try {
            var response = Net.client().send(Net.request(url).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }

            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    /** The face and the hat over it. */
    static BufferedImage face(BufferedImage skin, int size) {
        int unit = skin.getWidth() / 64;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(skin.getSubimage(8 * unit, 8 * unit, 8 * unit, 8 * unit), 0, 0, size, size, null);
        g.drawImage(skin.getSubimage(40 * unit, 8 * unit, 8 * unit, 8 * unit), 0, 0, size, size, null);
        g.dispose();
        return out;
    }
}
