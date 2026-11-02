package studios.milkdromeda.octo.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

/**
 * Octo Client, the client mod this launcher carries inside itself: put in a
 * folder of its own in Octo's data folder, never in the player's mods
 * folder. Octo Loader looks there itself, so Octo Client loads with the
 * Minecraft it is built for however the game is started.
 */
public final class OctoClientMod {
    static final String RESOURCE = "/octo/octo-client.jar";

    private OctoClientMod() {
    }

    /** The folder the launcher keeps Octo Client in. */
    public static Path folder() {
        return LauncherSettings.home().resolve("client");
    }

    /** Whether this build of the launcher carries Octo Client. */
    public static boolean available() {
        return OctoClientMod.class.getResource(RESOURCE) != null;
    }

    /** The Minecraft version the carried Octo Client is built for, or {@code null}. */
    public static String minecraftVersion() {
        try (InputStream in = OctoClientMod.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return null;
            }

            try (JarInputStream jar = new JarInputStream(in)) {
                Manifest manifest = jar.getManifest();
                return manifest == null ? null : manifest.getMainAttributes().getValue("Octo-Client-Minecraft");
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** Whether Octo Client runs with this Minecraft version: the one it is built for, and its patches. */
    public static boolean supports(String minecraft) {
        String built = minecraftVersion();
        return built != null && minecraft != null && (minecraft.equals(built) || minecraft.startsWith(built + "."));
    }

    /**
     * Makes the folder match the setting: Octo Client there when it is on,
     * gone when it is off, so the loader finds it only when wanted.
     */
    public static void sync(LauncherSettings settings) throws IOException {
        if (settings.octoClient && available()) {
            install();
        } else {
            remove();
        }
    }

    /** Takes Octo Client out of its folder. */
    public static void remove() throws IOException {
        Files.deleteIfExists(folder().resolve("octo-client.jar"));
    }

    /**
     * Puts the carried Octo Client in its folder, replacing an older one.
     *
     * @return the folder to load it from
     */
    public static Path install() throws IOException {
        Path folder = folder();
        Path target = folder.resolve("octo-client.jar");
        byte[] carried;

        try (InputStream in = OctoClientMod.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("this build of Octo Launcher carries no Octo Client");
            }

            carried = in.readAllBytes();
        }

        if (!Files.isRegularFile(target) || !Arrays.equals(Files.readAllBytes(target), carried)) {
            Files.createDirectories(folder);
            Path part = folder.resolve("octo-client.jar.part");
            Files.write(part, carried);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }

        return folder;
    }
}
