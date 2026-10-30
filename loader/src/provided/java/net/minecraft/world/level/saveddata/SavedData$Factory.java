package net.minecraft.world.level.saveddata;

import java.util.function.BiFunction;
import java.util.function.Supplier;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;

/**
 * {@code SavedData.Factory}, which 1.21.5 replaced with {@code SavedDataType}:
 * how a mod's saved data is made fresh and read back. Named as the nested
 * class it was, so the mods that make one find it; Octo turns it into a
 * {@code SavedDataType} where the game asks for one.
 */
public record SavedData$Factory<T extends SavedData>(Supplier<T> constructor,
        BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer, DataFixTypes type) {
    public SavedData$Factory(Supplier<T> constructor, BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer) {
        this(constructor, deserializer, null);
    }
}
