package net.minecraftforge.fml;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;

import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;
import studios.milkdromeda.octo.bridge.forge.ModInfos;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;

/** Forge's spelling of {@link net.neoforged.fml.ModList}, over the same one mod list. */
public final class ModList {
    private static final ModList INSTANCE = new ModList();

    private ModList() {
    }

    public static ModList get() {
        return INSTANCE;
    }

    public boolean isLoaded(String modId) {
        return ModInfos.isPresent(modId);
    }

    public int size() {
        return OctoRuntime.isRunning() ? OctoRuntime.get().loadedMods().size() : 0;
    }

    public List<String> getModIds() {
        return ModInfos.loadedIds();
    }

    /** @return the mod's container, or empty when it is not present */
    public Optional<? extends ModContainer> getModContainerById(String modId) {
        return isLoaded(modId) ? Optional.of(ForgeBuses.forgeContainerFor(modId)) : Optional.empty();
    }

    /** @return the container of the mod that owns {@code instance}, or empty */
    public Optional<? extends ModContainer> getModContainerByObject(Object instance) {
        if (instance == null || !OctoRuntime.isRunning()) {
            return Optional.empty();
        }

        for (LoadedMod mod : OctoRuntime.get().loadedMods()) {
            if (mod.instances().contains(instance)) {
                return Optional.of(ForgeBuses.forgeContainerFor(mod.id()));
            }
        }

        return Optional.empty();
    }

    /** @return the mod object a mod id was constructed into, or empty */
    public Optional<?> getModObjectById(String modId) {
        if (!OctoRuntime.isRunning()) {
            return Optional.empty();
        }

        return OctoRuntime.get().mod(modId)
                .map(LoadedMod::instances)
                .filter(instances -> !instances.isEmpty())
                .map(instances -> instances.get(0));
    }

    public List<IModInfo> getMods() {
        List<IModInfo> out = new ArrayList<>();
        getModIds().forEach(id -> out.add(ModInfos.forge(id)));
        return out;
    }

    /** @return the file a mod was read from, or {@code null} when it is absent */
    public ModFileInfo getModFileById(String modId) {
        return ModFileInfo.of(modId);
    }

    public void forEachModContainer(BiConsumer<String, ModContainer> action) {
        getModIds().forEach(id -> action.accept(id, ForgeBuses.forgeContainerFor(id)));
    }

    public <T> Stream<T> applyForEachModContainer(Function<ModContainer, T> function) {
        return getModIds().stream().map(id -> function.apply(ForgeBuses.forgeContainerFor(id)));
    }
}
