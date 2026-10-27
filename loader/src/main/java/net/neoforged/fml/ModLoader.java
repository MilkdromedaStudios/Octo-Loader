package net.neoforged.fml;

import net.neoforged.bus.api.Event;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;
import studios.milkdromeda.octo.bridge.forge.NeoRegistration;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * FML's mod loader, as far as NeoForge's own code calls it: the events it
 * hands to every mod's bus.
 *
 * <p>NeoForge registers everything through these. {@code GameData} posts each
 * registry's {@code RegisterEvent}, and {@code RegistryManager} the
 * {@code NewRegistryEvent}, with {@code postEventWrapContainerInModOrder}; a
 * stand-in that did nothing there meant no NeoForge mod's block, item or
 * registry ever existed. Posted here to NeoForge's own bus first and then to
 * each mod's, in load order, with that mod as the active one.
 */
public final class ModLoader {
    private ModLoader() {
    }

    public static void postEvent(Event event) {
        postEventWrapContainerInModOrder(event);
    }

    public static void postEventWrapContainerInModOrder(Event event) {
        ForgeBuses.neoBusFor(NeoRegistration.NEOFORGE).post(event);

        if (!OctoRuntime.isRunning()) {
            return;
        }

        String previous = ForgeBuses.activeModId();

        try {
            for (LoadedMod mod : OctoRuntime.get().loadedMods()) {
                if (mod.format() == ModFormat.NEOFORGE) {
                    ForgeBuses.setActive(mod.id());
                    ForgeBuses.neoBusFor(mod.id()).post(event);
                }
            }
        } finally {
            ForgeBuses.setActive(previous);
        }
    }

    /** Loading problems are reported by Octo itself, so there are never any here. */
    public static boolean hasErrors() {
        return false;
    }
}
