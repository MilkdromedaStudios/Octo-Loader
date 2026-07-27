package net.neoforged.fml.config;

import java.nio.file.Path;
import java.util.Locale;

import studios.milkdromeda.octo.runtime.OctoRuntime;

/** One configuration file belonging to one mod. */
public final class ModConfig {
    private final Type type;
    private final IConfigSpec spec;
    private final String modId;
    private final String fileName;

    public ModConfig(Type type, IConfigSpec spec, String modId, String fileName) {
        this.type = type;
        this.spec = spec;
        this.modId = modId;
        this.fileName = fileName;
    }

    public Type getType() {
        return type;
    }

    public IConfigSpec getSpec() {
        return spec;
    }

    public String getModId() {
        return modId;
    }

    public String getFileName() {
        return fileName;
    }

    public Path getFullPath() {
        return OctoRuntime.get().configDir().resolve(fileName);
    }

    /** Which side a config applies to, and therefore where the file lives. */
    public enum Type {
        /** Loaded on both sides, synchronised from the server on connect. */
        COMMON,
        /** Client-only settings, never sent anywhere. */
        CLIENT,
        /** Server-only settings, kept with the world. */
        SERVER,
        /** Read before the game starts and never reloaded. */
        STARTUP;

        public String extension() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
