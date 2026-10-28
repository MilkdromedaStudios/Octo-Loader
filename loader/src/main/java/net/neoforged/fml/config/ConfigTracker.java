package net.neoforged.fml.config;

import java.nio.file.Path;
import java.util.function.Function;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.config.ModConfigEvent;

import studios.milkdromeda.octo.bridge.forge.ConfigFiles;
import studios.milkdromeda.octo.bridge.forge.ForgeBuses;

/**
 * Where NeoForge keeps track of every mod's config files.
 *
 * <p>Octo opens a config as the mod registers it (see {@code ConfigFiles}),
 * so there is nothing for this to track. It exists because mods reach into
 * it: Moonlight, which Supplementaries and every other mod of its author
 * needs, swaps {@link #LOGGER} for a quieter one by reflection from a static
 * initialiser, and a missing field there failed all of them.
 */
public class ConfigTracker {
    public static final ConfigTracker INSTANCE = new ConfigTracker();

    /**
     * An SLF4J logger, as NeoForge's is. Declared as {@code Object} because
     * Octo does not compile against SLF4J; the game puts it on the class path,
     * and a reflective read or write of the field does not mind the type.
     */
    static final Object LOGGER = logger();

    private static Object logger() {
        try {
            return Class.forName("org.slf4j.LoggerFactory")
                    .getMethod("getLogger", String.class)
                    .invoke(null, ConfigTracker.class.getName());
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** Registers a config for a mod and reads its file, as the mod's own {@code registerConfig} does. */
    public ModConfig registerConfig(ModConfig.Type type, IConfigSpec spec, ModContainer container, String fileName) {
        container.registerConfig(type, spec, fileName);
        return container.getConfig(type).orElseThrow();
    }

    public ModConfig registerConfig(ModConfig.Type type, IConfigSpec spec, ModContainer container) {
        return registerConfig(type, spec, container, container.getModId() + "-" + type.extension() + ".toml");
    }

    /** Configs are opened as they are registered; there is nothing left to load. */
    public void loadConfigs(ModConfig.Type type, Path configDir) {
    }

    /**
     * Reads one config from {@code path} and tells its mod. Private to FML,
     * and reached by reflection: Moonlight loads its configs early through it.
     */
    void loadConfig(ModConfig modConfig, Path path, Function<ModConfig, ModConfigEvent> eventConstructor) {
        ConfigFiles.load(modConfig.getSpec(), () -> path);
        ForgeBuses.neoBusFor(modConfig.getModId()).post(eventConstructor.apply(modConfig));
    }

    public void unloadConfigs(ModConfig.Type type) {
    }
}
