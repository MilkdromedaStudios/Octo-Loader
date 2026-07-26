package studios.milkdromeda.octo.compat.fabric;

import java.util.function.BiConsumer;

import net.fabricmc.loader.api.ObjectShare;

import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * Fabric's cross-mod value store, backed by Octo's.
 *
 * <p>Because there is one store for the whole runtime, a Fabric mod publishing
 * {@code mymod:api} and a Forge mod reading it are talking to each other.
 */
public final class OctoObjectShare implements ObjectShare {
    @Override
    public Object get(String key) {
        return OctoRuntime.get().shareGet(key);
    }

    @Override
    public Object put(String key, Object value) {
        return OctoRuntime.get().sharePut(key, value);
    }

    @Override
    public Object putIfAbsent(String key, Object value) {
        return OctoRuntime.get().sharePutIfAbsent(key, value);
    }

    @Override
    public Object remove(String key) {
        return OctoRuntime.get().shareRemove(key);
    }

    @Override
    public void whenAvailable(String key, BiConsumer<String, Object> consumer) {
        OctoRuntime.get().shareWhenAvailable(key, consumer);
    }
}
