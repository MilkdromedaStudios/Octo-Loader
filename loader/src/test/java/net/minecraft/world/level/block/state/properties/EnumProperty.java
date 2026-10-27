package net.minecraft.world.level.block.state.properties;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/** Test fixture: the factories 26.x's EnumProperty has, and the values each one ends up with. */
public final class EnumProperty<T extends Enum<T>> {
    public final String name;
    public final List<T> values;

    private EnumProperty(String name, List<T> values) {
        this.name = name;
        this.values = List.copyOf(values);
    }

    public static <T extends Enum<T>> EnumProperty<T> create(String name, Class<T> type) {
        return new EnumProperty<>(name, Arrays.asList(type.getEnumConstants()));
    }

    public static <T extends Enum<T>> EnumProperty<T> create(String name, Class<T> type, Predicate<T> filter) {
        return new EnumProperty<>(name, Arrays.stream(type.getEnumConstants()).filter(filter).toList());
    }

    @SafeVarargs
    public static <T extends Enum<T>> EnumProperty<T> create(String name, Class<T> type, T... values) {
        return new EnumProperty<>(name, Arrays.asList(values));
    }

    public static <T extends Enum<T>> EnumProperty<T> create(String name, Class<T> type, List<T> values) {
        return new EnumProperty<>(name, values);
    }
}
