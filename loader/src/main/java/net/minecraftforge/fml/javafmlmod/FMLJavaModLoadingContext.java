package net.minecraftforge.fml.javafmlmod;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.ModContainer;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;

/**
 * How Forge mods reach their own buses, in both of the shapes Forge has used.
 *
 * <p>From 1.14 to 1.20, {@code FMLJavaModLoadingContext.get().getModEventBus()}
 * inside a constructor. From Forge 66 — 1.21.6 on, so every Forge mod for 26.x —
 * the context is handed to the constructor itself, and what it hands out is an
 * EventBus 7 {@link BusGroup}: {@code public MyMod(FMLJavaModLoadingContext
 * context)}. A mod with that constructor had no constructor Octo could call,
 * which is why GeckoLib and No Chat Reports did not load at all.
 */
public final class FMLJavaModLoadingContext {
    private final String modId;

    private FMLJavaModLoadingContext(String modId) {
        this.modId = modId;
    }

    /** One per mod; the same object every time it is asked for. */
    public static FMLJavaModLoadingContext of(String modId) {
        return ForgeBuses.forgeContextFor(modId, FMLJavaModLoadingContext::new);
    }

    /** The context of whichever mod is being constructed or dispatched to right now. */
    public static FMLJavaModLoadingContext get() {
        return of(ForgeBuses.activeModId());
    }

    public IEventBus getModEventBus() {
        return ForgeBuses.forgeBusFor(modId);
    }

    /** The mod's EventBus 7 bus group, for mod-bus events such as {@code FMLCommonSetupEvent}. */
    public BusGroup getModBusGroup() {
        return ForgeBuses.forgeBusGroupFor(modId);
    }

    public ModContainer getContainer() {
        return ForgeBuses.forgeContainerFor(modId);
    }

    public String getActiveNamespace() {
        return modId;
    }

    @Override
    public String toString() {
        return "FMLJavaModLoadingContext(" + modId + ")";
    }
}
