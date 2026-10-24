package net.minecraftforge.fml.common.event;

import java.io.File;

import studios.milkdromeda.octo.runtime.OctoRuntime;

/** The server is starting, 1.7 through 1.12. */
public class FMLServerStartingEvent {
    private final String modId;

    public FMLServerStartingEvent(String modId) {
        this.modId = modId;
    }

    public String getModId() {
        return modId;
    }

    /** The side the game is running as, which mods of this era branched on before anything else. */
    public net.minecraftforge.fml.relauncher.Side getSide() {
        return net.minecraftforge.fml.relauncher.Side.current();
    }

    /** The mod's own config file, the thing every mod of this era asked for first. */
    public File getSuggestedConfigurationFile() {
        return OctoRuntime.get().configDir().resolve(modId + ".cfg").toFile();
    }

    public File getModConfigurationDirectory() {
        return OctoRuntime.get().configDir().toFile();
    }
}
