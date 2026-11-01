package studios.milkdromeda.octo.launcher;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.imageio.ImageIO;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

/**
 * The skins a player keeps to switch between: PNGs in Octo's own folder, with
 * a name and arm style each, ready to wear with one click.
 */
public final class SkinLibrary {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One saved skin. The id is taken from the image, so the same skin is never kept twice. */
    public record Entry(String id, String name, boolean slim, long added) {
    }

    private final Path folder;

    public SkinLibrary(Path folder) {
        this.folder = folder;
    }

    public static SkinLibrary standard() {
        return new SkinLibrary(LauncherSettings.home().resolve("skins"));
    }

    private Path index() {
        return folder.resolve("skins.json");
    }

    public Path file(Entry entry) {
        return folder.resolve(entry.id() + ".png");
    }

    public synchronized List<Entry> list() {
        try {
            if (Files.isRegularFile(index())) {
                List<Entry> read = GSON.fromJson(Files.readString(index(), StandardCharsets.UTF_8),
                        new TypeToken<List<Entry>>() { }.getType());

                if (read != null) {
                    List<Entry> present = new ArrayList<>();

                    for (Entry entry : read) {
                        if (entry != null && entry.id() != null && Files.isRegularFile(file(entry))) {
                            present.add(entry);
                        }
                    }

                    return present;
                }
            }
        } catch (IOException | JsonParseException e) {
            // An empty library, then.
        }

        return new ArrayList<>();
    }

    /** Checks a PNG is a skin Minecraft takes: 64×64, or the old 64×32. */
    public static BufferedImage validate(byte[] png) throws IOException {
        BufferedImage image;

        try {
            image = ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            image = null;
        }

        if (image == null) {
            throw new IOException("That is not a PNG image. A skin is a 64×64 PNG.");
        }

        if (image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
            throw new IOException("That image is " + image.getWidth() + "×" + image.getHeight()
                    + ". A skin is a 64×64 PNG (or a 64×32 one from before 1.8).");
        }

        return image;
    }

    /** Adds a skin, or renames the one already there; returns its entry. */
    public synchronized Entry add(String name, byte[] png, boolean slim) throws IOException {
        validate(png);
        String id = id(png);
        List<Entry> entries = list();
        entries.removeIf(entry -> entry.id().equals(id));
        Entry entry = new Entry(id, name == null || name.isBlank() ? "Skin" : name.strip(), slim,
                System.currentTimeMillis());
        entries.add(0, entry);
        Files.createDirectories(folder);
        Files.write(file(entry), png);
        save(entries);
        return entry;
    }

    /** Whether a skin with this image is already kept. */
    public synchronized Entry find(byte[] png) {
        String id = id(png);
        return list().stream().filter(entry -> entry.id().equals(id)).findFirst().orElse(null);
    }

    public synchronized Entry update(Entry entry, String name, boolean slim) throws IOException {
        List<Entry> entries = list();
        Entry updated = new Entry(entry.id(), name, slim, entry.added());
        entries.replaceAll(existing -> existing.id().equals(entry.id()) ? updated : existing);
        save(entries);
        return updated;
    }

    public synchronized void remove(Entry entry) throws IOException {
        List<Entry> entries = list();
        entries.removeIf(existing -> existing.id().equals(entry.id()));
        save(entries);
        Files.deleteIfExists(file(entry));
    }

    public byte[] read(Entry entry) throws IOException {
        return Files.readAllBytes(file(entry));
    }

    private void save(List<Entry> entries) throws IOException {
        Files.createDirectories(folder);
        Path part = folder.resolve("skins.json.part");
        Files.writeString(part, GSON.toJson(entries) + "\n", StandardCharsets.UTF_8);
        Files.move(part, index(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * A skin's id, from its pixels rather than its file: Minecraft's texture
     * server re-encodes every skin, so the same skin comes back as a different
     * PNG.
     */
    static String id(byte[] png) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            BufferedImage image = null;

            try {
                image = ImageIO.read(new ByteArrayInputStream(png));
            } catch (IOException e) {
                // Not an image: its bytes, then.
            }

            if (image == null) {
                digest.update(png);
            } else {
                digest.update((image.getWidth() + "x" + image.getHeight()).getBytes(StandardCharsets.UTF_8));

                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int argb = image.getRGB(x, y);
                        // Fully clear pixels are the same pixel, whatever colour they carry.
                        int value = (argb >>> 24) == 0 ? 0 : argb;
                        digest.update(new byte[] { (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8),
                                (byte) value });
                    }
                }
            }

            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
