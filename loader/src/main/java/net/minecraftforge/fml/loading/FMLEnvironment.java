package net.minecraftforge.fml.loading;

import net.minecraftforge.api.distmarker.Dist;

import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.runtime.OctoRuntime;

/** Forge's spelling of {@link net.neoforged.fml.loading.FMLEnvironment}. */
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
