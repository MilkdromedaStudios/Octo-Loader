package net.minecraftforge.fml.loading;

import java.nio.file.Path;

import net.minecraftforge.api.distmarker.Dist;

import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * Forge's loader statics, which Forge kept static where NeoForge moved them
 * onto an instance.
 *
 * <p>Mods ask it the side before anything else exists — Collective decides in
 * its constructor whether to touch client code with {@code getDist().isClient()}
 * — and a stand-in's null there failed the whole mod.
 */
public final class FMLLoader {
    private FMLLoader() {
    }

    public static Dist getDist() {
        return FMLEnvironment.dist;
    }

    public static boolean isProduction() {
        return FMLEnvironment.production;
    }

    public static Path getGamePath() {
        return OctoRuntime.isRunning() ? OctoRuntime.get().gameDir() : Path.of(".");
    }

    public static LoadingModList getLoadingModList() {
        return LoadingModList.get();
    }

    public static String launcherHandlerName() {
        return "octo";
    }
}
