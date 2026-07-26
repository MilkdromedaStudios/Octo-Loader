package org.quiltmc.loader.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * Quilt's static loader facade, implemented on Octo's runtime.
 *
 * <p>Quilt Loader's own class is inseparable from its plugin system and virtual
 * file system, both of which Octo replaces, so this is a reimplementation of the
 * contract rather than a copy of it. What a mod sees is unchanged.
 */
public final class QuiltLoader {
    private QuiltLoader() {
    }

    public static boolean isModLoaded(String modId) {
        return OctoRuntime.isRunning() && OctoRuntime.get().isModLoaded(modId);
    }

    public static boolean isDevelopmentEnvironment() {
        return OctoRuntime.get().isDevelopment();
    }

    public static Path getGameDir() {
        return OctoRuntime.get().gameDir();
    }

    public static Path getConfigDir() {
        return OctoRuntime.get().configDir();
    }

    public static Path getCacheDir() {
        return OctoRuntime.get().context().octoDir().resolve("cache");
    }

    public static String[] getLaunchArguments(boolean sanitize) {
        return net.fabricmc.loader.impl.FabricLoaderImpl.INSTANCE.getLaunchArguments(sanitize);
    }

    public static Optional<Path> getModPath(String modId) {
        return OctoRuntime.get().mod(modId).map(LoadedMod::effectivePath);
    }

    public static List<String> getModIds() {
        return OctoRuntime.get().loadedMods().stream().map(LoadedMod::id).toList();
    }

    public static <T> List<T> getEntrypoints(String key, Class<T> type) {
        return net.fabricmc.loader.impl.FabricLoaderImpl.INSTANCE.getEntrypoints(key, type);
    }
}
