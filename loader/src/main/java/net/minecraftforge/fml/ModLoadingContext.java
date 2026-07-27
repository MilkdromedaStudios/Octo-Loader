package net.minecraftforge.fml;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.config.ModConfig;

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

    /** The bus the active mod's own events are posted on. */
    public IEventBus getModEventBus() {
        return ForgeBuses.forgeBusFor(ForgeBuses.activeModId());
    }

    /** The container of the mod being constructed right now. */
    public ModContainer getActiveContainer() {
        return ForgeBuses.forgeContainerFor(ForgeBuses.activeModId());
    }

    public void registerConfig(ModConfig.Type type, Object spec) {
        getActiveContainer().registerConfig(type, spec);
    }

    public void registerConfig(ModConfig.Type type, Object spec, String fileName) {
        getActiveContainer().registerConfig(type, spec, fileName);
    }

    public <T extends Record> void registerExtensionPoint(Class<? extends IExtensionPoint<T>> point,
            java.util.function.Supplier<T> extension) {
        getActiveContainer().registerExtensionPoint(point, extension);
    }
}
