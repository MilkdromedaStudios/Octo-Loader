package studios.milkdromeda.octo.compat.fabric;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.api.metadata.CustomValue;

/**
 * A mod's custom values, as Fabric hands them out.
 *
 * <p>Octo keeps them as plain Java values; this wraps one in Fabric's
 * {@link CustomValue} on the way out. Asking for the wrong kind throws
 * {@link ClassCastException}, which is what Fabric's own does and what mods
 * that probe a value's shape catch.
 */
class OctoCustomValue implements CustomValue {
    private final Object value;

    OctoCustomValue(Object value) {
        this.value = value;
    }

    static CustomValue of(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new OctoObject(map);
        }

        if (value instanceof List<?> list) {
            return new OctoArray(list);
        }

        return new OctoCustomValue(value);
    }

    static Map<String, CustomValue> all(Map<String, Object> values) {
        Map<String, CustomValue> out = new LinkedHashMap<>();
        values.forEach((key, value) -> out.put(key, of(value)));
        return out;
    }

    @Override
    public CvType getType() {
        return value == null ? CvType.NULL
                : value instanceof String ? CvType.STRING
                : value instanceof Number ? CvType.NUMBER
                : value instanceof Boolean ? CvType.BOOLEAN
                : value instanceof Map ? CvType.OBJECT : CvType.ARRAY;
    }

    @Override
    public CvObject getAsObject() {
        throw cannotConvert("an object");
    }

    @Override
    public CvArray getAsArray() {
        throw cannotConvert("an array");
    }

    @Override
    public String getAsString() {
        if (value instanceof String text) {
            return text;
        }

        throw cannotConvert("a string");
    }

    @Override
    public Number getAsNumber() {
        if (value instanceof Number number) {
            return number;
        }

        throw cannotConvert("a number");
    }

    @Override
    public boolean getAsBoolean() {
        if (value instanceof Boolean flag) {
            return flag;
        }

        throw cannotConvert("a boolean");
    }

    private ClassCastException cannotConvert(String wanted) {
        return new ClassCastException("can't convert " + getType().name() + " to " + wanted);
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }

    private static final class OctoObject extends OctoCustomValue implements CvObject {
        private final Map<String, CustomValue> entries = new LinkedHashMap<>();

        OctoObject(Map<?, ?> map) {
            super(map);
            map.forEach((key, value) -> entries.put(String.valueOf(key), of(value)));
        }

        @Override
        public CvObject getAsObject() {
            return this;
        }

        @Override
        public int size() {
            return entries.size();
        }

        @Override
        public boolean containsKey(String key) {
            return entries.containsKey(key);
        }

        @Override
        public CustomValue get(String key) {
            return entries.get(key);
        }

        @Override
        public Iterator<Map.Entry<String, CustomValue>> iterator() {
            List<Map.Entry<String, CustomValue>> copy = new ArrayList<>();
            entries.forEach((key, value) -> copy.add(new AbstractMap.SimpleImmutableEntry<>(key, value)));
            return copy.iterator();
        }
    }

    private static final class OctoArray extends OctoCustomValue implements CvArray {
        private final List<CustomValue> items = new ArrayList<>();

        OctoArray(List<?> list) {
            super(list);
            list.forEach(item -> items.add(of(item)));
        }

        @Override
        public CvArray getAsArray() {
            return this;
        }

        @Override
        public int size() {
            return items.size();
        }

        @Override
        public CustomValue get(int index) {
            return items.get(index);
        }

        @Override
        public Iterator<CustomValue> iterator() {
            return List.copyOf(items).iterator();
        }
    }
}
