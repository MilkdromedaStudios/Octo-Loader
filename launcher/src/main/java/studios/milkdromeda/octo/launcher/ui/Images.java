package studios.milkdromeda.octo.launcher.ui;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import studios.milkdromeda.octo.launcher.LauncherSettings;

/**
 * Icons from the web, fetched in the background and kept on disk, so a page
 * of mods draws at once the second time. One that cannot be read — Java reads
 * PNG, JPEG and GIF, not WebP — is a tile in the mod's own colour instead.
 */
public final class Images {
    private static final Map<String, Image> MEMORY = new ConcurrentHashMap<>();
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "octo-images");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    static {
        // WebP, which most of Modrinth's icons and screenshots are.
        try {
            javax.imageio.spi.IIORegistry.getDefaultInstance().registerServiceProvider(
                    new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi());
        } catch (Throwable e) {
            // Tiles for those, then.
        }
    }

    private Images() {
    }

    /**
     * A picture cut to fill a box (the middle of it, scaled to cover), with
     * rounded corners; nothing is handed over until it is there.
     */
    public static void cover(String url, int width, int height, int radius, Consumer<Image> ready) {
        if (url == null || url.isBlank()) {
            return;
        }

        String key = url + "@" + width + "x" + height;
        Image known = MEMORY.get(key);

        if (known != null) {
            ready.accept(known);
            return;
        }

        WORKERS.submit(() -> {
            BufferedImage image = fetch(url);

            if (image != null) {
                Image covered = cover(image, width, height, radius);
                MEMORY.put(key, covered);
                SwingUtilities.invokeLater(() -> ready.accept(covered));
            }
        });
    }

    static Image cover(BufferedImage source, int width, int height, int radius) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = Style.smooth(out.getGraphics());
        double scale = Math.max(width / (double) source.getWidth(), height / (double) source.getHeight());
        int drawnWidth = (int) Math.ceil(source.getWidth() * scale);
        int drawnHeight = (int) Math.ceil(source.getHeight() * scale);
        g.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, width, height, radius, radius));
        g.drawImage(source, (width - drawnWidth) / 2, (height - drawnHeight) / 2, drawnWidth, drawnHeight, null);
        g.dispose();
        return out;
    }

    /**
     * Hands over a placeholder at once, and the real image when it is there.
     */
    public static void load(String url, String name, Color colour, int size, int radius, Consumer<Image> ready) {
        Image placeholder = Widgets.monogram(name, colour, size, radius);
        ready.accept(placeholder);

        if (url == null || url.isBlank()) {
            return;
        }

        String key = url + "@" + size;
        Image known = MEMORY.get(key);

        if (known != null) {
            ready.accept(known);
            return;
        }

        WORKERS.submit(() -> {
            BufferedImage image = fetch(url);

            if (image != null) {
                Image scaled = Widgets.rounded(image, size, radius);
                MEMORY.put(key, scaled);
                SwingUtilities.invokeLater(() -> ready.accept(scaled));
            }
        });
    }

    /** An image from bytes (a mod jar's own icon), or a tile. */
    public static Image fromBytes(byte[] bytes, String name, int size, int radius) {
        if (bytes != null) {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));

                if (image != null) {
                    return Widgets.rounded(image, size, radius);
                }
            } catch (IOException | RuntimeException e) {
                // A tile, then.
            }
        }

        return Widgets.monogram(name, Widgets.colourFor(name), size, radius);
    }

    private static BufferedImage fetch(String url) {
        Path cache = LauncherSettings.home().resolve("cache").resolve("images")
                .resolve(Integer.toHexString(url.hashCode()) + ".img");

        try {
            byte[] bytes;

            if (Files.isRegularFile(cache)) {
                bytes = Files.readAllBytes(cache);
            } else {
                HttpResponse<byte[]> response = CLIENT.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15)).header("User-Agent", "Octo Launcher").build(),
                        HttpResponse.BodyHandlers.ofByteArray());

                if (response.statusCode() != 200) {
                    return null;
                }

                bytes = response.body();
                Files.createDirectories(cache.getParent());
                Files.write(cache, bytes);
            }

            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
