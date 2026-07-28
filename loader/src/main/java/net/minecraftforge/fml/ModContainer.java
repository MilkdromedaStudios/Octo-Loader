package net.minecraftforge.fml;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.config.ModConfig;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;

/** Forge's spelling of {@link net.neoforged.fml.ModContainer}. */
public class ModContainer {
    private final String modId;
    private final Map<Class<?>, Supplier<?>> extensionPoints = new ConcurrentHashMap<>();
    private final Map<ModConfig.Type, ModConfig> configs = new ConcurrentHashMap<>();

    public ModContainer(String modId) {
        this.modId = modId;
    }

    public final String getModId() {
        return modId;
    }

    public final String getNamespace() {
        return modId;
    }

    public IEventBus getEventBus() {
        return ForgeBuses.forgeBusFor(modId);
    }

    /** What this mod says about itself; see {@link net.neoforged.fml.ModContainer#getModInfo()}. */
    public net.minecraftforge.forgespi.language.IModInfo getModInfo() {
        return studios.milkdromeda.octo.bridge.forge.ModInfos.forge(modId);
    }

    public void registerConfig(ModConfig.Type type, Object spec) {
        registerConfig(type, spec, modId + "-" + type.extension() + ".toml");
    }

    public void registerConfig(ModConfig.Type type, Object spec, String fileName) {
        configs.put(type, new ModConfig(type, spec, modId, fileName));
    }

    public Optional<ModConfig> getConfig(ModConfig.Type type) {
        return Optional.ofNullable(configs.get(type));
    }

    public <T extends Record> void registerExtensionPoint(Class<? extends IExtensionPoint<T>> point,
            Supplier<T> extension) {
        extensionPoints.put(point, extension);
    }

    @SuppressWarnings("unchecked")
    public <T extends Record> Optional<T> getCustomExtension(Class<? extends IExtensionPoint<T>> point) {
        Supplier<?> supplier = extensionPoints.get(point);
        return supplier == null ? Optional.empty() : Optional.ofNullable((T) supplier.get());
    }

    public final <T extends Event> void acceptEvent(T event) {
        getEventBus().post(event);
    }

    @Override
    public String toString() {
        return "ModContainer(" + modId + ")";
    }
}
