package net.minecraftforge.fml;

import java.util.List;
import java.util.stream.Collectors;

import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * Forge's view of what is loaded, answered from Octo's runtime.
 *
 * <p>A Forge mod calling {@code ModList.get().isLoaded("sodium")} gets a true
 * answer even though Sodium is a Fabric mod, because there is only one list.
 */
public final class ModList {
    private static final ModList INSTANCE = new ModList();

    private ModList() {
    }

    public static ModList get() {
        return INSTANCE;
    }

    public boolean isLoaded(String modId) {
        return OctoRuntime.isRunning() && OctoRuntime.get().isModLoaded(modId);
    }

    public int size() {
        return OctoRuntime.isRunning() ? OctoRuntime.get().loadedMods().size() : 0;
    }

    public List<String> getModIds() {
        if (!OctoRuntime.isRunning()) {
            return List.of();
        }

        return OctoRuntime.get().loadedMods().stream()
                .map(studios.milkdromeda.octo.runtime.LoadedMod::id)
                .collect(Collectors.toList());
    }
}
