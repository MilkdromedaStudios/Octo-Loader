package studios.milkdromeda.octoloader.translate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Provenance bookkeeping for generated jars. Every jar Octo Loader emits
 * carries manifest attributes linking it to its source, so outputs are
 * recognizable, cache-checkable, and cleaned up when stale.
 */
public final class TranslationCache {
    public static final String GENERATED_SUFFIX = ".octo.jar";
    public static final Attributes.Name ATTR_TRANSLATED_FROM = new Attributes.Name("Octo-Translated-From");
    public static final Attributes.Name ATTR_SOURCE_SHA256 = new Attributes.Name("Octo-Source-Sha256");
    public static final Attributes.Name ATTR_TRANSLATOR = new Attributes.Name("Octo-Translator");

    private TranslationCache() {
    }

    public static boolean isGeneratedJar(Path jar) {
        return jar.getFileName().toString().endsWith(GENERATED_SUFFIX);
    }

    public static Path outputFor(Path sourceJar, Path outputDir) {
        String base = sourceJar.getFileName().toString();
        if (base.toLowerCase().endsWith(".jar")) base = base.substring(0, base.length() - 4);
        return outputDir.resolve(base + GENERATED_SUFFIX);
    }

    /** Whether {@code output} exists and was generated from the current content of {@code source}. */
    public static boolean isFresh(Path source, Path output) {
        Optional<String> recorded = readAttribute(output, ATTR_SOURCE_SHA256);
        return recorded.isPresent() && recorded.get().equals(sha256(source));
    }

    public static Optional<String> readAttribute(Path jar, Attributes.Name name) {
        if (!Files.isRegularFile(jar)) return Optional.empty();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest manifest = jf.getManifest();
            if (manifest == null) return Optional.empty();
            return Optional.ofNullable(manifest.getMainAttributes().getValue(name));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash " + file, e);
        }
    }
}
