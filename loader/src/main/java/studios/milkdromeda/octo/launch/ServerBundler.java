package studios.milkdromeda.octo.launch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Opens the server jar Mojang has shipped since 1.18.
 *
 * <p>{@code server.jar} is not the server. It is a bundler: a few kilobytes of
 * {@code net.minecraft.bundler.Main}, with the real server jar and every library
 * it needs packed inside it under {@code META-INF/versions} and
 * {@code META-INF/libraries}. Run normally, the bundler unpacks those next to
 * itself and starts the server in a class loader of its own — which is exactly
 * what Octo cannot let happen, because the game has to be inside Octo's class
 * loader for a single mod to apply. Handed the bundler as it is, Octo found
 * {@code version.json}, decided it was the game, and then could not find
 * {@code net.minecraft.server.Main} in it.
 *
 * <p>So the bundler's own work is done here instead: the nested jars are
 * extracted once, checked against the hashes the bundler lists, and used as the
 * game and its libraries from then on. A second launch finds them already
 * there and extracts nothing.
 */
public final class ServerBundler {
    private static final OctoLog LOG = OctoLog.of(ServerBundler.class);

    /** What a bundler turned out to contain once unpacked. */
    public record Unpacked(List<Path> gameJars, List<Path> libraries, String mainClass, String version) {
    }

    private ServerBundler() {
    }

    /** Whether a jar is Mojang's bundler rather than a game jar. */
    public static boolean isBundler(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return false;
        }

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry("META-INF/versions.list") != null
                    && zip.getEntry("net/minecraft/bundler/Main.class") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Unpacks a bundler into {@code root}, laid out as the bundler itself would
     * lay it out ({@code versions/} and {@code libraries/}), so a vanilla launch
     * from the same directory finds the same files.
     */
    public static Optional<Unpacked> unpack(Path bundler, Path root) {
        try (ZipFile zip = new ZipFile(bundler.toFile())) {
            List<Path> gameJars = extractListed(zip, "versions", root.resolve("versions"));
            List<Path> libraries = extractListed(zip, "libraries", root.resolve("libraries"));
            String mainClass = text(zip, "META-INF/main-class").orElse(GameProvider.SERVER_MAIN);
            String version = gameJars.isEmpty() ? "" : versionOf(zip);

            if (gameJars.isEmpty()) {
                LOG.warn("{} looks like a server bundler but lists no server jar", bundler.getFileName());
                return Optional.empty();
            }

            LOG.info("server bundler {} unpacked: {} game jar(s), {} librarie(s), main class {}",
                    bundler.getFileName(), gameJars.size(), libraries.size(), mainClass);
            return Optional.of(new Unpacked(gameJars, libraries, mainClass, version));
        } catch (IOException | RuntimeException e) {
            LOG.error("could not unpack the server bundler {}: {}", bundler, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Extracts every entry a {@code <kind>.list} file names.
     *
     * <p>Each line is {@code <sha256>\t<id>\t<path>}, with the path relative to
     * {@code META-INF/<kind>/}. A file already on disk with the right hash is
     * kept, which is what makes every launch after the first one free.
     */
    private static List<Path> extractListed(ZipFile zip, String kind, Path into) throws IOException {
        List<Path> out = new ArrayList<>();
        Optional<String> listing = text(zip, "META-INF/" + kind + ".list");

        if (listing.isEmpty()) {
            return out;
        }

        for (String line : listing.get().split("\\R")) {
            String[] parts = line.split("\t");

            if (parts.length < 3) {
                continue;
            }

            String hash = parts[0].trim();
            String path = parts[2].trim();
            ZipEntry entry = zip.getEntry("META-INF/" + kind + "/" + path);

            if (entry == null) {
                LOG.warn("the server bundler lists {} but does not contain it", path);
                continue;
            }

            Path target = into.resolve(path).normalize();

            if (!target.startsWith(into)) {
                // A listing that walks out of its directory is not one Mojang wrote.
                LOG.warn("ignoring bundler entry outside its directory: {}", path);
                continue;
            }

            if (!Files.isRegularFile(target) || !hash.equalsIgnoreCase(sha256(target))) {
                Files.createDirectories(target.getParent());
                Path temporary = target.resolveSibling(target.getFileName() + ".octo-tmp");

                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
                }

                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                LOG.debug("extracted {} from the server bundler", path);
            }

            out.add(target);
        }

        return out;
    }

    private static String versionOf(ZipFile zip) {
        return text(zip, "META-INF/versions.list")
                .flatMap(listing -> listing.lines().findFirst())
                .map(line -> line.split("\t"))
                .filter(parts -> parts.length >= 2)
                .map(parts -> parts[1].trim())
                .orElse("");
    }

    private static Optional<String> text(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);

        if (entry == null) {
            return Optional.empty();
        }

        try (InputStream in = zip.getInputStream(entry)) {
            return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];

            for (int read; (read = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }

            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "";
        }
    }
}
