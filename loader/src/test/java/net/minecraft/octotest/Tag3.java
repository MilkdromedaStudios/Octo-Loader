package net.minecraft.octotest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Stands in for 26.x's {@code CompoundTag}, whose getters answer Optionals or take a default. */
public final class Tag3 {
    private final Map<String, Object> values = new LinkedHashMap<>();

    public Tag3 put(String key, Object value) {
        values.put(key, value);
        return this;
    }

    public boolean contains(String key) {
        return values.containsKey(key);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Optional<Integer> getInt(String key) {
        return values.get(key) instanceof Integer value ? Optional.of(value) : Optional.empty();
    }

    public int getIntOr(String key, int fallback) {
        return getInt(key).orElse(fallback);
    }

    public Optional<String> getString(String key) {
        return values.get(key) instanceof String value ? Optional.of(value) : Optional.empty();
    }

    public Optional<Tag3> getCompound(String key) {
        return values.get(key) instanceof Tag3 value ? Optional.of(value) : Optional.empty();
    }

    public Tag3 getCompoundOrEmpty(String key) {
        return getCompound(key).orElseGet(Tag3::new);
    }

    public Tag3 getListOrEmpty(String key) {
        return getCompoundOrEmpty(key);
    }
}
