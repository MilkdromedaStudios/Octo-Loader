package net.minecraft.world.level.storage;

import net.minecraft.nbt.CompoundTag;

/** A {@link ValueOutput} building a tag, as the game's does. */
public class TagValueOutput implements ValueOutput {
    private final CompoundTag output = new CompoundTag();

    public CompoundTag buildResult() {
        return output;
    }
}
