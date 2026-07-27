package studios.milkdromeda.octo.mixin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

/**
 * The blackboard mixin and its config plugins share.
 *
 * <p>Under ModLauncher this is backed by a genuinely global map so that several
 * class loaders can see it. Octo has one class loader for the whole game, so a
 * plain map is the whole of it.
 */
public final class OctoPropertyService implements IGlobalPropertyService {
    private final Map<String, Object> properties = new ConcurrentHashMap<>();

    @Override
    public IPropertyKey resolveKey(String name) {
        return new Key(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key) {
        return (T) properties.get(key.toString());
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        if (value == null) {
            properties.remove(key.toString());
        } else {
            properties.put(key.toString(), value);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        Object value = properties.get(key.toString());
        return value == null ? defaultValue : (T) value;
    }

    @Override
    public String getPropertyString(IPropertyKey key, String defaultValue) {
        Object value = properties.get(key.toString());
        return value == null ? defaultValue : value.toString();
    }

    private record Key(String name) implements IPropertyKey {
        @Override
        public String toString() {
            return name;
        }
    }
}
