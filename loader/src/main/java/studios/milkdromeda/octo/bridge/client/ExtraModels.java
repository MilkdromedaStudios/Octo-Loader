package studios.milkdromeda.octo.bridge.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;
import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Models mods ask for by name, loaded with the game's own.
 *
 * <p>Until 26.x a mod could ask the game for a model no block state names —
 * a cogwheel, a water wheel's paddles, a press head — through its loader's
 * model event ({@code ModelEvent.RegisterAdditional} on NeoForge and Forge),
 * and read the baked model back when baking was done ({@code
 * BakingCompleted.getModels()}, {@code ModelManager.getModel}). Create draws
 * every moving part that way, through Flywheel's partial models, and so do
 * its add-ons and a good share of every other mod with moving parts. The
 * loaders' patched game fires those events; this game is not patched, and
 * the models were nulls.
 *
 * <p>So Octo does what the patch did, in this game's terms. As the game
 * discovers its models ({@code ModelDiscovery.resolve}), the mods are asked
 * for theirs and each is added as a root, so its parents and textures load
 * with everything else. When the game applies what it baked ({@code
 * ModelManager.apply}), each is baked with the game's own baker into a
 * single-variant block model, and the mods are told baking is done, with a
 * map that answers their keys. Hooks: {@code ExtraModelHooks}.
 */
public final class ExtraModels {
    private static final OctoLog LOG = OctoLog.of(ExtraModels.class);
    private static final String MODEL = "net.minecraft.client.resources.model.";
    private static final String DISPATCH = "net.minecraft.client.renderer.block.dispatch.";

    /** What mods asked for this load: by model id, the key each asked with. */
    private static final Map<String, Object> REQUESTED = new ConcurrentHashMap<>();
    /** The baked models, by model id; a mod may replace one through the map it is handed. */
    private static final Map<String, Object> BAKED = new ConcurrentHashMap<>();

    private static volatile Object bakery;
    private static volatile Object materials;
    private static volatile Object manager;
    private static volatile Object missing;

    private ExtraModels() {
    }

    // ------------------------------------------------------------- requests

    /** A mod asking for a model: {@code register(location)} on its loader's event. */
    public static void request(Object location) {
        String id = idOf(location);

        if (id != null && REQUESTED.putIfAbsent(id, location) == null) {
            LOG.debug("a mod asked for the model {} ({})", id, location == null ? null : location.getClass().getName());
        }
    }

    /**
     * The model id a key names: an id, or a {@code ModelResourceLocation}
     * whose variant is {@code standalone} (1.21) or absent. A key naming a
     * block state variant keeps it after a {@code #}.
     */
    static String idOf(Object key) {
        if (key == null) {
            return null;
        }

        String text = key.toString();
        int hash = text.indexOf('#');
        String id = hash < 0 ? text : text.substring(0, hash);
        String variant = hash < 0 ? "" : text.substring(hash + 1);

        if (id.isEmpty()) {
            return null;
        }

        if (id.indexOf(':') < 0) {
            id = "minecraft:" + id;
        }

        return variant.isEmpty() || variant.equals("standalone") ? id : id + "#" + variant;
    }

    // ---------------------------------------------------------------- hooks

    /**
     * {@code ModelDiscovery.resolve()}, before it runs: asks the mods for
     * their models and adds each as a root of the discovery.
     */
    public static void discover(Object discovery) {
        try {
            // Mods make the models they ask for in their client setup, which
            // their loaders run before the game reads its models.
            studios.milkdromeda.octo.hook.GameHooks.awaitClientPhases(60_000);
            REQUESTED.clear();
            ForgeBuses.postToEveryMod(new net.neoforged.neoforge.client.event.ModelEvent$RegisterAdditional());
            ForgeBuses.postToEveryMod(new net.minecraftforge.client.event.ModelEvent$RegisterAdditional());

            List<String> ids = standaloneIds();

            if (ids.isEmpty()) {
                LOG.debug("no mod asked for a model by name");
                return;
            }

            ClassLoader loader = discovery.getClass().getClassLoader();
            Class<?> resolvable = Class.forName(MODEL + "ResolvableModel", false, loader);
            Class<?> resolver = Class.forName(MODEL + "ResolvableModel$Resolver", false, loader);
            Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", false, loader);
            Method mark = resolver.getMethod("markDependency", identifier);
            Method parse = identifier.getMethod("parse", String.class);

            Object root = Proxy.newProxyInstance(loader, new Class<?>[] { resolvable }, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "resolveDependencies" -> {
                        for (String id : ids) {
                            mark.invoke(args[0], parse.invoke(null, id));
                        }

                        return null;
                    }
                    case "toString" -> {
                        return "Octo extra models";
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    default -> {
                        return null;
                    }
                }
            });

            discovery.getClass().getMethod("addRoot", resolvable).invoke(discovery, root);
            LOG.info("{} model(s) mods ask for by name will load with the game's", ids.size());
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("could not add the models mods ask for by name: {}", Failures.describe(Failures.unwrap(e)));
            OctoLog.detail(Failures.unwrap(e));
        }
    }

    /** {@code ModelBakery.bakeModels(materials, executor)}, as it starts: the bakery and its materials. */
    public static void baking(Object modelBakery, Object materialBaker) {
        bakery = modelBakery;
        materials = materialBaker;
    }

    /**
     * {@code ModelManager.apply(state)}, once it has run: bakes what the mods
     * asked for and tells them baking is done.
     */
    public static void applied(Object modelManager, Object reloadState) {
        manager = modelManager;
        Object result = null;

        try {
            ClassLoader loader = modelManager.getClass().getClassLoader();
            result = call(reloadState, "bakedModels");
            Object missingModels = call(result, "missingModels");
            missing = call(missingModels, "block");
            BAKED.clear();

            List<String> ids = standaloneIds();

            if (!ids.isEmpty() && bakery != null && materials != null) {
                bakeAll(loader, missingModels, ids);
            }
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("could not bake the models mods ask for by name: {}", Failures.describe(Failures.unwrap(e)));
            OctoLog.detail(Failures.unwrap(e));
        }

        bakingCompleted(modelManager, result);
    }

    private static void bakeAll(ClassLoader loader, Object missingModels, List<String> ids) throws Exception {
        Class<?> bakeryType = Class.forName(MODEL + "ModelBakery", false, loader);
        Class<?> materialType = Class.forName(MODEL + "sprite.MaterialBaker", false, loader);
        Class<?> internerType = Class.forName(MODEL + "ModelBaker$Interner", false, loader);
        Class<?> missingType = Class.forName(MODEL + "ModelBakery$MissingModels", false, loader);
        Class<?> bakerType = Class.forName(MODEL + "ModelBaker", false, loader);
        Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", false, loader);
        Class<?> state = Class.forName(DISPATCH + "ModelState", false, loader);
        Class<?> part = Class.forName(DISPATCH + "BlockStateModelPart", false, loader);

        Constructor<?> internerMaker = Class.forName(MODEL + "ModelBakery$InternerImpl", false, loader)
                .getDeclaredConstructor();
        internerMaker.setAccessible(true);
        Constructor<?> bakerMaker = Class.forName(MODEL + "ModelBakery$ModelBakerImpl", false, loader)
                .getDeclaredConstructor(bakeryType, materialType, internerType, missingType);
        bakerMaker.setAccessible(true);
        Object baker = bakerMaker.newInstance(bakery, materials, internerMaker.newInstance(), missingModels);

        Method bake = Class.forName(MODEL + "SimpleModelWrapper", false, loader)
                .getMethod("bake", bakerType, identifier, state);
        Method parse = identifier.getMethod("parse", String.class);
        Object identity = Class.forName(DISPATCH + "BlockModelRotation", true, loader).getField("IDENTITY").get(null);
        Constructor<?> single = Class.forName(DISPATCH + "SingleVariant", false, loader).getConstructor(part);
        int failed = 0;

        for (String id : ids) {
            try {
                BAKED.put(id, single.newInstance(bake.invoke(null, baker, parse.invoke(null, id), identity)));
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                failed++;
                LOG.debug("could not bake {}: {}", id, Failures.describe(Failures.unwrap(e)));
            }
        }

        LOG.info("baked {} model(s) mods ask for by name{}", BAKED.size(),
                failed == 0 ? "" : "; " + failed + " could not be baked and show as missing");
    }

    /** Tells NeoForge and Forge mods baking is done, with the events their loaders have for it on this game. */
    private static void bakingCompleted(Object modelManager, Object result) {
        ClassLoader loader = modelManager.getClass().getClassLoader();

        post(loader, "net.neoforged.neoforge.client.event.ModelEvent$BakingCompleted", modelManager, result, bakery);
        post(loader, "net.minecraftforge.client.event.ModelEvent$BakingCompleted", modelManager, bakery);
    }

    /**
     * Posts the loader's own event, when its API jar is there to have one.
     *
     * <p>Its constructor can reach for what the loader's patch added to the
     * game — NeoForge's reads the standalone models off the baking result —
     * and fail; the event is then made without it and its fields set from
     * the arguments, which is all its getters read.
     */
    private static void post(ClassLoader loader, String eventName, Object... arguments) {
        // Asked of the jar first: the class loader would stand in for a
        // missing loader class, and an invented event reaches nobody.
        if (loader.getResource(eventName.replace('.', '/') + ".class") == null) {
            return;
        }

        Class<?> type;

        try {
            type = Class.forName(eventName, true, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return;
        }

        Object event = null;

        for (Constructor<?> constructor : type.getConstructors()) {
            if (constructor.getParameterCount() != arguments.length) {
                continue;
            }

            try {
                event = constructor.newInstance(arguments);
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                event = withoutConstructor(type, arguments);
            }

            break;
        }

        if (event == null) {
            return;
        }

        try {
            ForgeBuses.postToEveryMod(event);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("could not tell mods their models are baked ({}): {}", type.getSimpleName(),
                    Failures.describe(Failures.unwrap(e)));
            OctoLog.detail(Failures.unwrap(e));
        }
    }

    /** The event allocated without running its constructor, each field set from the argument of its type. */
    private static Object withoutConstructor(Class<?> type, Object[] arguments) {
        try {
            java.lang.reflect.Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Object event = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);

            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                for (Object argument : arguments) {
                    if (argument != null && field.getType().isInstance(argument)) {
                        field.setAccessible(true);
                        field.set(event, argument);
                        break;
                    }
                }
            }

            return event;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.debug("could not make a {}: {}", type.getName(), e.toString());
            return null;
        }
    }

    // -------------------------------------------------------------- lookups

    /**
     * {@code ModelManager.getModel(key)}: a model asked for by name, a block
     * state's model by its variant, or the missing model; never null, since
     * the code asking draws whatever it gets.
     */
    public static Object model(Object modelManager, Object key) {
        Object found = find(modelManager, key);
        return found != null ? found : missing(modelManager);
    }

    /** {@code ModelManager.getMissingModel()}. */
    public static Object missing(Object modelManager) {
        if (missing == null && modelManager != null) {
            try {
                missing = call(call(modelManager, "getBlockStateModelSet"), "missingModel");
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
            }
        }

        return missing;
    }

    private static Object find(Object modelManager, Object key) {
        String id = idOf(key);

        if (id == null) {
            return null;
        }

        Object baked = BAKED.get(id);

        if (baked != null) {
            return baked;
        }

        int hash = id.indexOf('#');
        Object owner = modelManager != null ? modelManager : manager;

        if (hash < 0 || owner == null) {
            return null;
        }

        try {
            Object blockState = blockState(owner.getClass().getClassLoader(), id.substring(0, hash),
                    id.substring(hash + 1));
            return blockState == null ? null : call(call(owner, "getBlockStateModelSet"), "get", blockState);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            return null;
        }
    }

    /** The block state a model variant names: {@code minecraft:lever#face=wall,facing=north,powered=false}. */
    private static Object blockState(ClassLoader loader, String id, String variant) throws Exception {
        Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", false, loader);
        Object blocks = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader)
                .getField("BLOCK").get(null);
        Object block = blocks.getClass().getMethod("getValue", identifier)
                .invoke(blocks, identifier.getMethod("parse", String.class).invoke(null, id));
        Object definition = call(block, "getStateDefinition");

        for (Object candidate : (Iterable<?>) call(definition, "getPossibleStates")) {
            if (variantOf(candidate).equals(variant)) {
                return candidate;
            }
        }

        return variant.isEmpty() ? call(block, "defaultBlockState") : null;
    }

    /** A block state as its model variant names it: properties by name, {@code name=value}, comma-separated. */
    private static String variantOf(Object blockState) throws Exception {
        List<Object> properties = new ArrayList<>((java.util.Collection<?>) call(blockState, "getProperties"));
        Method propertyName = null;
        Map<String, Object> byName = new java.util.TreeMap<>();

        for (Object property : properties) {
            if (propertyName == null) {
                propertyName = publicMethod(property.getClass(), "getName", 0);
            }

            byName.put((String) propertyName.invoke(property), property);
        }

        StringBuilder out = new StringBuilder();

        for (Map.Entry<String, Object> entry : byName.entrySet()) {
            Object property = entry.getValue();
            Object value = publicMethod(blockState.getClass(), "getValue", 1).invoke(blockState, property);
            Method valueName = null;

            for (Method method : property.getClass().getMethods()) {
                if (method.getName().equals("getName") && method.getParameterCount() == 1) {
                    valueName = method;
                    break;
                }
            }

            if (out.length() > 0) {
                out.append(',');
            }

            out.append(entry.getKey()).append('=').append(valueName == null ? value : valueName.invoke(property, value));
        }

        return out.toString();
    }

    /**
     * {@code BakingCompleted.getModels()}, {@code ModifyBakingResult.getModels()}
     * and {@code ModelBakery.getBakedTopLevelModels()}: the map the mods read
     * their models from, keyed the way each mod asked.
     */
    public static Map<Object, Object> models(Object owner) {
        return new AbstractMap<>() {
            @Override
            public Object get(Object key) {
                return model(manager, key);
            }

            @Override
            public boolean containsKey(Object key) {
                return find(manager, key) != null;
            }

            @Override
            public Object put(Object key, Object value) {
                String id = idOf(key);

                if (id == null || id.indexOf('#') >= 0 || value == null) {
                    // A block state's model is the game's; replacing it means
                    // replacing how the game meshes the block, which a mod's
                    // model from another version cannot do here.
                    LOG.debug("a mod replaced the model {}; block state models are kept", key);
                    return find(manager, key);
                }

                return BAKED.put(id, value);
            }

            @Override
            public Set<Map.Entry<Object, Object>> entrySet() {
                Set<Map.Entry<Object, Object>> entries = new LinkedHashSet<>();

                for (Map.Entry<String, Object> requested : REQUESTED.entrySet()) {
                    Object baked = BAKED.get(requested.getKey());

                    if (baked != null) {
                        entries.add(new AbstractMap.SimpleImmutableEntry<>(requested.getValue(), baked));
                    }
                }

                return entries;
            }
        };
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> standaloneIds() {
        List<String> ids = new ArrayList<>();

        for (String id : REQUESTED.keySet()) {
            if (id.indexOf('#') < 0) {
                ids.add(id);
            }
        }

        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    private static Object call(Object target, String name, Object... arguments) throws Exception {
        return publicMethod(target.getClass(), name, arguments.length).invoke(target, arguments);
    }

    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    private static Method publicMethod(Class<?> type, String name, int arity) throws NoSuchMethodException {
        String key = type.getName() + "." + name + "/" + arity;
        Method cached = METHODS.get(key);

        if (cached != null) {
            return cached;
        }

        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            for (Method method : at.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == arity && !method.isBridge()) {
                    method.setAccessible(true);
                    METHODS.put(key, method);
                    return method;
                }
            }
        }

        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arity) {
                method.setAccessible(true);
                METHODS.put(key, method);
                return method;
            }
        }

        throw new NoSuchMethodException(type.getName() + "." + name + "/" + arity);
    }

    /** What was asked for and baked, for tests. */
    static Map<String, Object> requested() {
        return new LinkedHashMap<>(REQUESTED);
    }
}
