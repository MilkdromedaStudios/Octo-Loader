package net.minecraft.core.component;

import java.util.Set;

/** The game's component map, reduced to what a stand-in view of one has to answer. */
public interface DataComponentMap {
    Set<Object> keySet();

    Object get(Object type);

    default boolean has(Object type) {
        return get(type) != null;
    }

    default int size() {
        return keySet().size();
    }
}
