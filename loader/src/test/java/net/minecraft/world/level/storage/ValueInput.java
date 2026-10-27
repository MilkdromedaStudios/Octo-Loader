package net.minecraft.world.level.storage;

import net.minecraft.core.HolderLookup;

/** What 26.x reads a block entity through. */
public interface ValueInput {
    HolderLookup.Provider lookup();
}
