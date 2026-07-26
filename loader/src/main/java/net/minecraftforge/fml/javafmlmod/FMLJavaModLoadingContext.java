package net.minecraftforge.fml.javafmlmod;

import net.minecraftforge.eventbus.api.IEventBus;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;

/**
 * How Forge mods from 1.14 through 1.19 reach their event bus.
 *
 * <p>{@code FMLJavaModLoadingContext.get().getModEventBus()} inside a mod's
 * constructor is one of the most common lines in the ecosystem, so it has to
 * work and has to return the bus belonging to the mod being constructed.
 */
public final class FMLJavaModLoadingContext {
    private static final FMLJavaModLoadingContext INSTANCE = new FMLJavaModLoadingContext();

    private FMLJavaModLoadingContext() {
    }

    public static FMLJavaModLoadingContext get() {
        return INSTANCE;
    }

    public IEventBus getModEventBus() {
        return ForgeBuses.forgeBusFor(ForgeBuses.activeModId());
    }
}
