package studios.milkdromeda.octo.bridge;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Client assets written before 1.21.4, made readable to this game: the item
 * definitions and the names that changed shape since.
 *
 * <ul>
 * <li><b>Item definitions.</b> Since 1.21.4 an item is drawn from
 * {@code assets/<namespace>/items/<id>.json}, which says which model to use.
 * Before, the game took {@code models/item/<id>.json} straight. A mod from then
 * has only the model, so every one of its items drew as the missing texture.
 * For each mod item model with no definition, a plain one is made that uses
 * it, as the game's own definitions for ordinary items do. A real definition
 * always wins.</li>
 * <li><b>Block item names.</b> A block's item was named by the block's key
 * ({@code block.create.cogwheel}) until 1.21.2; now it has a key of its own
 * ({@code item.create.cogwheel}), which those mods' language files do not
 * have, so the item showed its raw key. Each {@code block.<namespace>.} entry
 * is offered under {@code item.<namespace>.} too, where nothing is there.</li>
 * </ul>
 */
public final class LegacyClientAssets {
    private static final OctoLog LOG = OctoLog.of(LegacyClientAssets.class);

    private LegacyClientAssets() {
    }

    // ---------------------------------------------------------- definitions

    /** One item definition to make: where it goes, and the model it draws. */
    record Definition(String namespace, String itemsPath, String model) {
        byte[] json() {
            return ("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + model + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * The definitions missing for these item models.
     *
     * @param itemModels  item model files, as namespace and path ({@code models/item/cogwheel.json})
     * @param definitions the item definition files there are, as {@code namespace:items/cogwheel.json}
     */
    static List<Definition> missing(Collection<String[]> itemModels, Set<String> definitions) {
        List<Definition> out = new ArrayList<>();

        for (String[] model : itemModels) {
            String namespace = model[0];
            String path = model[1];

            // The game's own items all have definitions; a mod's model under its name replaces a model, not an item.
            if (namespace.equals("minecraft") || !path.startsWith("models/item/") || !path.endsWith(".json")) {
                continue;
            }

            String id = path.substring("models/item/".length(), path.length() - ".json".length());
            String itemsPath = "items/" + id + ".json";

            if (!id.isEmpty() && !definitions.contains(namespace + ":" + itemsPath)) {
                out.add(new Definition(namespace, itemsPath, namespace + ":item/" + id));
            }
        }

        return out;
    }

    /**
     * Called with the item definition files {@code ClientItemInfoLoader} found,
     * before it reads them: those files, and one made for each mod item model
     * without one.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static Map withItemDefinitions(Map found, Object resourceManager) {
        try {
            ClassLoader loader = resourceManager.getClass().getClassLoader();
            Class<?> converterType = Class.forName("net.minecraft.resources.FileToIdConverter", false, loader);
            Class<?> managerType = Class.forName("net.minecraft.server.packs.resources.ResourceManager", false, loader);
            Object converter = converterType.getMethod("json", String.class).invoke(null, "models/item");
            Map<?, ?> models = (Map<?, ?>) converterType.getMethod("listMatchingResources", managerType)
                    .invoke(converter, resourceManager);

            if (models.isEmpty()) {
                return found;
            }

            Class<?> identifierType = models.keySet().iterator().next().getClass();
            java.lang.reflect.Method namespace = identifierType.getMethod("getNamespace");
            java.lang.reflect.Method path = identifierType.getMethod("getPath");
            java.util.Set<String> have = new java.util.HashSet<>();

            for (Object id : found.keySet()) {
                have.add(namespace.invoke(id) + ":" + path.invoke(id));
            }

            List<String[]> modelFiles = new ArrayList<>();
            Map<String, Object> resourceOf = new HashMap<>();

            for (Map.Entry<?, ?> entry : models.entrySet()) {
                String ns = (String) namespace.invoke(entry.getKey());
                String file = (String) path.invoke(entry.getKey());
                modelFiles.add(new String[] { ns, file });
                resourceOf.put(ns + ":" + file, entry.getValue());
            }

            List<Definition> made = missing(modelFiles, have);

            if (made.isEmpty()) {
                return found;
            }

            Class<?> resourceType = Class.forName("net.minecraft.server.packs.resources.Resource", false, loader);
            Class<?> packType = Class.forName("net.minecraft.server.packs.PackResources", false, loader);
            Class<?> supplierType = Class.forName("net.minecraft.server.packs.resources.IoSupplier", false, loader);
            java.lang.reflect.Constructor<?> newResource = resourceType.getConstructor(packType, supplierType);
            java.lang.reflect.Method source = resourceType.getMethod("source");
            java.lang.reflect.Method identifier = identifierType.getMethod("fromNamespaceAndPath", String.class,
                    String.class);
            Map out = new HashMap(found);
            Map<String, Integer> counts = new TreeMap<>();

            for (Definition definition : made) {
                Object model = resourceOf.get(definition.namespace() + ":models/item/"
                        + definition.model().substring(definition.namespace().length() + ":item/".length()) + ".json");
                byte[] json = definition.json();
                Object supplier = Proxy.newProxyInstance(loader, new Class<?>[] { supplierType },
                        stream(json));
                out.put(identifier.invoke(null, definition.namespace(), definition.itemsPath()),
                        newResource.newInstance(source.invoke(model), supplier));
                counts.merge(definition.namespace(), 1, Integer::sum);
            }

            LOG.info("item definitions made from 1.21-style item models, so these items draw: {}", counts);
            return out;
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("mods' older item models could not be given item definitions: {}",
                    Failures.describe(Failures.unwrap(e)));
            return found;
        }
    }

    private static InvocationHandler stream(byte[] content) {
        return (proxy, method, arguments) -> {
            if (method.getName().equals("get")) {
                return new ByteArrayInputStream(content);
            }

            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, arguments);
            }

            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> "Octo item definition";
            };
        };
    }

    // ---------------------------------------------------------------- names

    /**
     * Called with the translations {@code ClientLanguage} is made with: those,
     * with each mod's {@code block.} entries also under {@code item.} where
     * that key has nothing.
     */
    public static Map<String, String> withBlockItemNames(Map<String, String> storage) {
        try {
            Map<String, String> added = null;

            for (Map.Entry<String, String> entry : storage.entrySet()) {
                String key = entry.getKey();

                if (!key.startsWith("block.") || key.startsWith("block.minecraft.")) {
                    continue;
                }

                String itemKey = "item." + key.substring("block.".length());

                if (!storage.containsKey(itemKey)) {
                    if (added == null) {
                        added = new HashMap<>();
                    }

                    added.put(itemKey, entry.getValue());
                }
            }

            if (added == null) {
                return storage;
            }

            Map<String, String> out = new HashMap<>(storage);
            out.putAll(added);
            return Collections.unmodifiableMap(out);
        } catch (RuntimeException e) {
            return storage;
        }
    }
}
