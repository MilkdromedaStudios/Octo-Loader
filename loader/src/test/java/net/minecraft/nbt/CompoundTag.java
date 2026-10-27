package net.minecraft.nbt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The game's compound tag, as far as a mod's save and load methods need it. */
public class CompoundTag {
    public final Map<String, Object> values = new LinkedHashMap<>();

    public void putInt(String key, int value) {
        values.put(key, value);
    }

    public int getInt(String key) {
        return values.get(key) instanceof Integer value ? value : 0;
    }

    public void putIntArray(String key, int[] value) {
        values.put(key, new IntArrayTag(value));
    }

    public Object get(String key) {
        return values.get(key);
    }

    public Optional<int[]> getIntArray(String key) {
        return values.get(key) instanceof IntArrayTag tag ? Optional.of(tag.getAsIntArray()) : Optional.empty();
    }

    public CompoundTag merge(CompoundTag other) {
        values.putAll(other.values);
        return this;
    }
}
