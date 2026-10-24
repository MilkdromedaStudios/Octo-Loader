package net.minecraftforge.fml.common;

import java.io.File;

import studios.milkdromeda.octo.bridge.forge.ModInfos;
import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * Forge's loader object from 1.7 to 1.12, as mods asked it questions: whether
 * another mod is here, and where configs go. Answered from Octo's one mod list,
 * so a 1.12 mod asking about a Fabric mod gets the truth.
 */
public final class Loader {
    private static final Loader INSTANCE = new Loader();

    private Loader() {
    }

    public static Loader instance() {
        return INSTANCE;
    }

    public static boolean isModLoaded(String modId) {
        return ModInfos.isPresent(modId);
    }

    public File getConfigDir() {
        return OctoRuntime.get().configDir().toFile();
    }

    public String getMCVersionString() {
        return "Minecraft " + (OctoRuntime.isRunning() ? OctoRuntime.get().minecraftVersion() : "");
    }
}
