package net.neoforged.fml.loading;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * The directories a mod asks the loader for.
 *
 * <p>Answered from Octo's own launch context, so a NeoForge mod and a Fabric mod
 * asking "where is the config directory" get the same directory.
 */
public enum FMLPaths {
    GAMEDIR(""),
    MODSDIR("mods"),
    CONFIGDIR("config"),
    JIJ_CACHEDIR(".cache/jij"),
    FMLCONFIG("config/fml.toml");

    private final String relative;

    FMLPaths(String relative) {
        this.relative = relative;
    }

    /** The path relative to the game directory. */
    public Path relative() {
        return Path.of(relative);
    }

    /** The absolute path, created if it is a directory that does not exist yet. */
    public Path get() {
        Path gameDir = OctoRuntime.isRunning() ? OctoRuntime.get().gameDir() : Path.of(".").toAbsolutePath();

        if (this == CONFIGDIR && OctoRuntime.isRunning()) {
            return createIfDirectory(OctoRuntime.get().configDir());
        }

        if (relative.isEmpty()) {
            return gameDir;
        }

        Path resolved = gameDir.resolve(relative);
        return this == FMLCONFIG ? resolved : createIfDirectory(resolved);
    }

    public static Path getOrCreateGameRelativePath(Path path) {
        Path gameDir = OctoRuntime.isRunning() ? OctoRuntime.get().gameDir() : Path.of(".").toAbsolutePath();
        return createIfDirectory(gameDir.resolve(path));
    }

    private static Path createIfDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException ignored) {
            // A mod asking for a directory it cannot have is not a reason to
            // refuse it the path: it will fail more usefully at the point of use.
        }

        return path;
    }
}
