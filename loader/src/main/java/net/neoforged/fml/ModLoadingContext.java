package net.neoforged.fml;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;

/** The mod currently being constructed. */
public final class ModLoadingContext {
    private static final ModLoadingContext INSTANCE = new ModLoadingContext();

    private ModLoadingContext() {
    }

    public static ModLoadingContext get() {
        return INSTANCE;
    }

    public String getActiveNamespace() {
        return ForgeBuses.activeModId();
    }

    public IEventBus getModEventBus() {
        return ForgeBuses.neoBusFor(ForgeBuses.activeModId());
    }

    /** The container of the mod being constructed right now. */
    public ModContainer getActiveContainer() {
        return ForgeBuses.neoContainerFor(ForgeBuses.activeModId());
    }

    public void registerConfig(ModConfig.Type type, IConfigSpec spec) {
        getActiveContainer().registerConfig(type, spec);
    }

    public void registerConfig(ModConfig.Type type, IConfigSpec spec, String fileName) {
        getActiveContainer().registerConfig(type, spec, fileName);
    }

    public <T extends IExtensionPoint> void registerExtensionPoint(Class<T> point, java.util.function.Supplier<T> extension) {
        getActiveContainer().registerExtensionPoint(point, extension);
    }
}
