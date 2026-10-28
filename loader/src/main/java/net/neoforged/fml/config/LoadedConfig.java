package net.neoforged.fml.config;

import java.nio.file.Path;

import com.electronwill.nightconfig.core.CommentedConfig;

import net.neoforged.fml.event.config.ModConfigEvent;

import studios.milkdromeda.octo.bridge.forge.ConfigFiles;
import studios.milkdromeda.octo.bridge.forge.ForgeBuses;

/**
 * A config file as read for its spec: what NeoForge hands
 * {@code acceptConfig}. Moonlight builds these itself, by reflection, to
 * apply the configs a server sends.
 */
record LoadedConfig(CommentedConfig config, Path path, ModConfig modConfig) implements IConfigSpec.ILoadedConfig {
    @Override
    public void save() {
        if (path != null) {
            ConfigFiles.write(path, config);
        }

        ForgeBuses.neoBusFor(modConfig.getModId()).post(new ModConfigEvent.Reloading(modConfig));
    }
}
