package net.minecraft.world.level.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/** A {@link ValueInput} reading a tag, as the game's does. */
public class TagValueInput implements ValueInput {
    private final CompoundTag input;

    public TagValueInput(CompoundTag input) {
        this.input = input;
    }

    @Override
    public HolderLookup.Provider lookup() {
        return null;
    }
}
