package net.minecraftforge.client.event;

import net.minecraftforge.eventbus.api.Event;

import studios.milkdromeda.octo.bridge.client.ExtraModels;

/**
 * Forge's request for models no block state names, from 1.19.2 until Forge
 * dropped it; Forge 1.20.1's Create and every add-on built on its partial
 * models ask for their parts through it. Fired and answered as NeoForge's
 * is ({@link ExtraModels}).
 */
public class ModelEvent$RegisterAdditional extends Event {
    /** Asks for the model with this id. */
    public void register(Object location) {
        ExtraModels.request(location);
    }
}
