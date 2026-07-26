package studios.milkdromeda.octo.installer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The loader jar carried inside the installer.
 *
 * <p>The installer ships the loader rather than downloading it, so installing
 * works with no network at all — which matters for the players most likely to
 * want this: people reviving an old modpack on a machine that is not online.
 */
public final class EmbeddedLoader {
    public static final String RESOURCE = "/META-INF/octo/octo-loader.jar";

    private EmbeddedLoader() {
    }

    public static boolean isAvailable() {
        try (InputStream in = EmbeddedLoader.class.getResourceAsStream(RESOURCE)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Writes the bundled loader to {@code target}, creating parent directories. */
    public static void extractTo(Path target) throws IOException {
        try (InputStream in = EmbeddedLoader.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("this installer was built without the loader embedded (" + RESOURCE + ")");
            }

            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
