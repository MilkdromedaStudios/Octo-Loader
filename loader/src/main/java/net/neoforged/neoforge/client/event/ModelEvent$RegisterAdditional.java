package net.neoforged.neoforge.client.event;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

import studios.milkdromeda.octo.bridge.client.ExtraModels;

/**
 * NeoForge's request for models no block state names, until 1.21.4.
 *
 * <p>Mods registered their loose models through it, Flywheel's partial
 * models among them: every rotating part Create draws. NeoForge 26 replaced
 * it with standalone model keys, and the game it ships for fires neither on
 * a game its installer has not patched. Octo fires this as the game loads
 * its models, bakes what was asked for, and hands it back through {@code
 * ModelEvent.BakingCompleted.getModels()} ({@link ExtraModels}).
 *
 * <p>Named as the nested class it was, since that is the name mods link
 * against; the {@code ModelEvent} it sat in is NeoForge's own.
 */
public class ModelEvent$RegisterAdditional extends Event implements IModBusEvent {
    /**
     * Asks for the model at {@code location}: a {@code ModelResourceLocation}
     * with the {@code standalone} variant, or before 1.21, the model's id.
     */
    public void register(Object location) {
        ExtraModels.request(location);
    }
}
