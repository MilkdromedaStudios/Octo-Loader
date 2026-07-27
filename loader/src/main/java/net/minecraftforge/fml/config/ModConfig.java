package net.minecraftforge.fml.config;

import java.nio.file.Path;
import java.util.Locale;

import studios.milkdromeda.octo.runtime.OctoRuntime;

/** Forge's spelling of {@link net.neoforged.fml.config.ModConfig}. */
public final class ModConfig {
    private final Type type;
    private final Object spec;
    private final String modId;
    private final String fileName;

    public ModConfig(Type type, Object spec, String modId, String fileName) {
        this.type = type;
        this.spec = spec;
        this.modId = modId;
        this.fileName = fileName;
    }

    public Type getType() {
        return type;
    }

    public Object getSpec() {
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

    public enum Type {
        COMMON,
        CLIENT,
        SERVER;

        public String extension() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
