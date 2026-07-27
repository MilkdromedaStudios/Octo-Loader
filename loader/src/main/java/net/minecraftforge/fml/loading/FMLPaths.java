package net.minecraftforge.fml.loading;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import studios.milkdromeda.octo.runtime.OctoRuntime;

/** Forge's spelling of {@link net.neoforged.fml.loading.FMLPaths}. */
public enum FMLPaths {
    GAMEDIR(""),
    MODSDIR("mods"),
    CONFIGDIR("config"),
    FMLCONFIG("config/fml.toml");

    private final String relative;

    FMLPaths(String relative) {
        this.relative = relative;
    }

    public Path relative() {
        return Path.of(relative);
    }

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
            // See the NeoForge copy: an unavailable directory fails better later.
        }

        return path;
    }
}
