package net.neoforged.fml;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;

/**
 * A mod, as NeoForge hands it to itself.
 *
 * <p>Current NeoForge mods take this as a constructor parameter and use it to
 * register their config and their extension points, which made a missing
 * {@code ModContainer} enough on its own to stop Create and Flywheel from being
 * constructed at all — the class could not be loaded, so the constructor could
 * not be resolved, so nothing in the mod ran.
 *
 * <p>Everything answerable from Octo's runtime is answered properly. Config
 * registration is recorded rather than acted on: Octo has no TOML config system,
 * and a mod that registers a spec and then reads defaults from it behaves the
 * same either way.
 */
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
        return ForgeBuses.neoBusFor(modId);
    }

    /**
     * What this mod says about itself.
     *
     * <p>Reached through {@code ModList.get().getModContainerById(id)} and used
     * for exactly one thing in practice: reading another mod's version. Absent,
     * it is a {@code NoSuchMethodError} in the caller's static initialiser.
     */
    public net.neoforged.neoforgespi.language.IModInfo getModInfo() {
        return studios.milkdromeda.octo.bridge.forge.ModInfos.neo(modId);
    }

    public void registerConfig(ModConfig.Type type, IConfigSpec spec) {
        registerConfig(type, spec, modId + "-" + type.extension() + ".toml");
    }

    public void registerConfig(ModConfig.Type type, IConfigSpec spec, String fileName) {
        configs.put(type, new ModConfig(type, spec, modId, fileName));
    }

    public Optional<ModConfig> getConfig(ModConfig.Type type) {
        return Optional.ofNullable(configs.get(type));
    }

    public <T extends IExtensionPoint> void registerExtensionPoint(Class<T> point, T extension) {
        extensionPoints.put(point, () -> extension);
    }

    public <T extends IExtensionPoint> void registerExtensionPoint(Class<T> point, Supplier<T> extension) {
        extensionPoints.put(point, extension);
    }

    public <T extends IExtensionPoint> Optional<T> getCustomExtension(Class<T> point) {
        Supplier<?> supplier = extensionPoints.get(point);
        return supplier == null ? Optional.empty() : Optional.ofNullable(point.cast(supplier.get()));
    }

    public final <T extends Event> void acceptEvent(T event) {
        getEventBus().post(event);
    }

    public final <T extends Event> void acceptEvent(EventPriority priority, T event) {
        getEventBus().post(event);
    }

    @Override
    public String toString() {
        return "ModContainer(" + modId + ")";
    }
}
