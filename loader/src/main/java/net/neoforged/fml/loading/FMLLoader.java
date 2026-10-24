package net.neoforged.fml.loading;

import java.nio.file.Path;

import net.neoforged.api.distmarker.Dist;

import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * NeoForge's loader object, as mods from FML 10 on see it.
 *
 * <p>NeoForge turned this from a bag of statics into an instance reached through
 * {@link #getCurrent()}, and current mods ask it for the side, the game
 * directory and the loading mod list — FerriteCore reads its own config path
 * through it in a mixin plugin, before any mod has run. Without a real one, the
 * stand-in answered null and FerriteCore's mixins never loaded.
 *
 * <p>Mods built for the static shape ({@code FMLLoader.getDist()} on 1.21.1) are
 * pointed at {@code getCurrent()} first by the loader-API rewrite, so both
 * generations of call reach the same answers.
 */
public final class FMLLoader {
    private static final FMLLoader CURRENT = new FMLLoader();

    private FMLLoader() {
    }

    public static FMLLoader getCurrent() {
        return CURRENT;
    }

    public static FMLLoader getCurrentOrNull() {
        return OctoRuntime.isRunning() ? CURRENT : null;
    }

    public Dist getDist() {
        return FMLEnvironment.dist;
    }

    public LoadingModList getLoadingModList() {
        return LoadingModList.get();
    }

    public Path getGameDir() {
        return OctoRuntime.isRunning() ? OctoRuntime.get().gameDir() : Path.of("").toAbsolutePath();
    }

    /** FML 4's name for the same thing. */
    public Path getGamePath() {
        return getGameDir();
    }

    public boolean isProduction() {
        return FMLEnvironment.production;
    }

    public ClassLoader getCurrentClassLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : FMLLoader.class.getClassLoader();
    }

    public String getLauncherInfo() {
        return "Octo Loader " + OctoRuntime.VERSION;
    }
}
