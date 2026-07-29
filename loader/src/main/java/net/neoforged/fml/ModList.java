package net.neoforged.fml;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;

import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;
import studios.milkdromeda.octo.bridge.forge.ModInfos;
import studios.milkdromeda.octo.bridge.forge.ScanData;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * NeoForge's view of what is loaded, answered from Octo's runtime.
 *
 * <p>A NeoForge mod calling {@code ModList.get().isLoaded("sodium")} gets a true
 * answer even though Sodium is a Fabric mod, because there is only one list.
 *
 * <p>The rest of the surface is here for the same reason {@code isLoaded} is:
 * mods walk this list while they are being constructed to find out who else is
 * present, and a lookup Octo has not implemented is not a null answer — it is a
 * {@code NoSuchMethodError} inside a static initialiser, which costs the whole
 * mod rather than the one branch that asked.
 */
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
        return isLoaded(modId) ? Optional.of(ForgeBuses.neoContainerFor(modId)) : Optional.empty();
    }

    /** @return the container of the mod that owns {@code instance}, or empty */
    public Optional<? extends ModContainer> getModContainerByObject(Object instance) {
        if (instance == null || !OctoRuntime.isRunning()) {
            return Optional.empty();
        }

        for (LoadedMod mod : OctoRuntime.get().loadedMods()) {
            if (mod.instances().contains(instance)) {
                return Optional.of(ForgeBuses.neoContainerFor(mod.id()));
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
        getModIds().forEach(id -> out.add(ModInfos.neo(id)));
        return out;
    }

    /** @return the file a mod was read from, or {@code null} when it is absent */
    public ModFileInfo getModFileById(String modId) {
        return ModFileInfo.of(modId);
    }

    /**
     * What every loaded mod's classes said about themselves.
     *
     * <p>This is how a NeoForge mod finds the classes it is meant to construct.
     * JEI's entire content comes through here — it looks for {@code @JeiPlugin}
     * and instantiates whatever carries it — so an empty or missing answer is not
     * a degraded JEI, it is a {@code NullPointerException} in JEI's constructor
     * and no JEI at all.
     */
    public List<ModFileScanData> getAllScanData() {
        return ScanData.all();
    }

    public void forEachModContainer(BiConsumer<String, ModContainer> action) {
        getModIds().forEach(id -> action.accept(id, ForgeBuses.neoContainerFor(id)));
    }

    public <T> Stream<T> applyForEachModContainer(Function<ModContainer, T> function) {
        return getModIds().stream().map(id -> function.apply(ForgeBuses.neoContainerFor(id)));
    }
}
