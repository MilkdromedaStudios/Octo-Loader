package net.neoforged.neoforge.common.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.Tag;

/**
 * NeoForge's interface for something saved to and read from a tag, until
 * NeoForge 26 moved saving to value inputs and outputs. Inventories, tanks
 * and energy buffers from before still implement it.
 */
public interface INBTSerializable<T extends Tag> {
    T serializeNBT(HolderLookup.Provider provider);

    void deserializeNBT(HolderLookup.Provider provider, T nbt);
}
