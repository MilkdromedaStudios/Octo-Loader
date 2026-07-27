package net.neoforged.fml.loading;

import net.neoforged.api.distmarker.Dist;

import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * Which side the game is running on, and whether this is a development run.
 *
 * <p>Both the field and the accessor exist because mods from different NeoForge
 * generations reach for different ones, and a mod compiled against either
 * spelling has to link.
 */
public final class FMLEnvironment {
    public static final Dist dist = currentDist();
    public static final boolean production = !isDevelopment();
    public static final String dist_name = dist.name();

    private FMLEnvironment() {
    }

    public static Dist getDist() {
        return dist;
    }

    public static boolean isProduction() {
        return production;
    }

    private static Dist currentDist() {
        if (!OctoRuntime.isRunning()) {
            return Dist.CLIENT;
        }

        return OctoRuntime.get().side() == Side.CLIENT ? Dist.CLIENT : Dist.DEDICATED_SERVER;
    }

    private static boolean isDevelopment() {
        return OctoRuntime.isRunning() && OctoRuntime.get().isDevelopment();
    }
}
