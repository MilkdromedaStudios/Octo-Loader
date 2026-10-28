package studios.milkdromeda.octo.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Each Forge and NeoForge mod's jar, as a pack of the game's: its data on the
 * server, its assets on the client.
 *
 * <p>The game only reads data and assets from its packs, and a mod's jar is
 * one only because its loader makes it one — NeoForge's patched game adds a
 * pack per mod. Fabric API does the same for Fabric mods; nothing did for the
 * others under Octo, so none of Create's recipes, loot tables or tags existed
 * on a server, and none of its textures or models on a client. Each pack is
 * required and on top of the game's own, as NeoForge places them.
 */
public final class ModPacks {
    private static final OctoLog LOG = OctoLog.of(ModPacks.class);

    private ModPacks() {
    }

    /** The loader API jars — NeoForge's, Forge's — whose data is the loader's own: its common tags. */
    private static volatile List<Path> libraries = List.of();

    /** The game's class loader, which the pack repository came from. */
    private static volatile ClassLoader gameLoader;

    /** Called by the launcher with the loader API jars it put on the class path. */
    public static void libraries(List<Path> jars) {
        libraries = List.copyOf(jars);
    }

    /** Called at the end of {@code PackRepository}'s constructor. */
    public static void attach(Object repository) {
        try {
            Field sourcesField = repository.getClass().getDeclaredField("sources");
            sourcesField.setAccessible(true);
            Set<?> sources = (Set<?>) sourcesField.get(repository);
            Object packType = packType(sources);

            if (packType == null) {
                return;
            }

            List<LoadedMod> mods = modsWithPacks();

            if (mods.isEmpty() && libraries.isEmpty()) {
                return;
            }

            ClassLoader loader = repository.getClass().getClassLoader();
            gameLoader = loader;
            Class<?> sourceType = Class.forName("net.minecraft.server.packs.repository.RepositorySource", false, loader);
            Object source = Proxy.newProxyInstance(loader, new Class<?>[] { sourceType }, (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "loadPacks" -> load(loader, mods, castConsumer(arguments[0]), packType);
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "equals" -> {
                        return proxy == arguments[0];
                    }
                    case "toString" -> {
                        return "Octo's mod packs";
                    }
                    default -> {
                    }
                }

                return null;
            });

            Set<Object> widened = new LinkedHashSet<>(sources);
            widened.add(source);
            Class<?> immutable = Class.forName("com.google.common.collect.ImmutableSet", true, loader);
            sourcesField.set(repository, immutable.getMethod("copyOf", java.util.Collection.class).invoke(null, widened));
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("mods' data and assets could not be added to the game's packs: {}",
                    Failures.describe(Failures.unwrap(e)));
            OctoLog.detail(Failures.unwrap(e));
        }
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Object> castConsumer(Object consumer) {
        return (Consumer<Object>) consumer;
    }

    /** The type of the built-in pack among the sources: data on a server, resources on a client. */
    private static Object packType(Set<?> sources) throws IllegalAccessException {
        for (Object source : sources) {
            for (Class<?> type = source.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType().getName().equals("net.minecraft.server.packs.PackType")) {
                        field.setAccessible(true);
                        return field.get(source);
                    }
                }
            }
        }

        return null;
    }

    private static List<LoadedMod> modsWithPacks() {
        try {
            return OctoRuntime.get().mods().stream()
                    .filter(mod -> mod.format() == ModFormat.NEOFORGE || mod.format() == ModFormat.FORGE)
                    .filter(mod -> !mod.isFailed() && mod.effectivePath() != null && Files.exists(mod.effectivePath()))
                    .toList();
        } catch (IllegalStateException e) {
            return List.of();
        }
    }

    private static void load(ClassLoader loader, List<LoadedMod> mods, Consumer<Object> onLoad, Object packType) {
        boolean data = String.valueOf(packType).equals("SERVER_DATA");

        // The loader's own first: NeoForge's c: tags are what mods' recipes name.
        for (Path library : libraries) {
            String id = library.getFileName().toString().replaceAll("-.*$", "");

            try {
                onLoad.accept(pack(loader, id, id.equals("neoforge") ? "NeoForge" : "Forge", library,
                        path -> !path.equals("tags") && !path.startsWith("tags/"), null));
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.warn("{}'s data could not be made a pack: {}", id, Failures.describe(Failures.unwrap(e)));
            }
        }

        for (LoadedMod mod : mods) {
            try {
                String name = mod.metadata().name() == null ? mod.id() : mod.metadata().name();
                boolean translated = data && mod.isFromAnotherVersion();
                onLoad.accept(pack(loader, mod.id(), name, mod.effectivePath(),
                        translated ? ModPacks::isWorldData : null, translated ? mod.id() : null));
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.warn("{}'s data and assets could not be made a pack: {}", mod.id(),
                        Failures.describe(Failures.unwrap(e)));
            }
        }
    }

    /**
     * @param hidden     which data paths are left out, or {@code null} for none
     * @param translated the id of the translated mod whose data this is, which
     *                   has its recipes rewritten, or {@code null}
     */
    private static Object pack(ClassLoader loader, String id, String name, Path jar,
            java.util.function.Predicate<String> hidden, String translated) throws ReflectiveOperationException {
        Class<?> component = type(loader, "net.minecraft.network.chat.Component");
        Class<?> packSource = type(loader, "net.minecraft.server.packs.repository.PackSource");
        Class<?> location = type(loader, "net.minecraft.server.packs.PackLocationInfo");
        Class<?> supplierType = type(loader, "net.minecraft.server.packs.repository.Pack$ResourcesSupplier");
        Class<?> metadata = type(loader, "net.minecraft.server.packs.repository.Pack$Metadata");
        Class<?> compatibility = type(loader, "net.minecraft.server.packs.repository.PackCompatibility");
        Class<?> features = type(loader, "net.minecraft.world.flag.FeatureFlagSet");
        Class<?> selection = type(loader, "net.minecraft.server.packs.PackSelectionConfig");
        Class<?> position = type(loader, "net.minecraft.server.packs.repository.Pack$Position");
        Class<?> pack = type(loader, "net.minecraft.server.packs.repository.Pack");

        Object title = component.getMethod("literal", String.class).invoke(null, name);
        Object info = location.getConstructor(String.class, component, packSource, Optional.class)
                .newInstance("mod/" + id, title, packSource.getField("DEFAULT").get(null), Optional.empty());

        Object supplier = Files.isDirectory(jar)
                ? type(loader, "net.minecraft.server.packs.PathPackResources$PathResourcesSupplier")
                        .getConstructor(Path.class).newInstance(jar)
                : type(loader, "net.minecraft.server.packs.FilePackResources$FileResourcesSupplier")
                        .getConstructor(Path.class).newInstance(jar);

        if (hidden != null) {
            supplier = filtered(loader, supplierType, supplier, hidden, translated != null);

            if (translated != null) {
                LOG.info("{}'s world generation and other world-load data is left out: it is from another Minecraft, "
                        + "and one entry this one cannot read would stop worlds from loading", translated);
            }
        }

        Object description = component.getMethod("literal", String.class).invoke(null, name + " (Octo Loader)");
        Object meta = metadata.getConstructor(component, compatibility, features, List.class).newInstance(description,
                enumConstant(compatibility, "COMPATIBLE"), features.getMethod("of").invoke(null), List.of());
        Object config = selection.getConstructor(boolean.class, position, boolean.class)
                .newInstance(true, enumConstant(position, "TOP"), false);
        return pack.getConstructor(location, supplierType, metadata, selection).newInstance(info, supplier, meta, config);
    }

    /**
     * A pack's data with some of it left out, and, for a translated mod,
     * its recipes rewritten for this game ({@link DataMigration}).
     *
     * <p>A translated mod's entries in the registries a world loads before
     * anything else — biomes, features, enchantments, damage types — are left
     * out with their tags: their format moves between releases, and one entry
     * this game cannot read stops worlds from loading at all, as Create's ore
     * features and its potato cannon enchantment from 1.21.1 did. The world
     * loads, without the mod's ores. Of the loader's own data only its tags
     * are kept: the rest replaces the game's recipes and advancements with
     * versions that need the loader's patched game.
     */
    private static Object filtered(ClassLoader loader, Class<?> supplierType, Object supplier,
            java.util.function.Predicate<String> hidden, boolean migrate) throws ReflectiveOperationException {
        Class<?> resourcesType = type(loader, "net.minecraft.server.packs.PackResources");
        return Proxy.newProxyInstance(loader, new Class<?>[] { supplierType }, (proxy, method, arguments) -> {
            Object result = invoke(supplier, method, arguments);

            if (method.getName().equals("openResources") && result instanceof java.util.stream.Stream<?> stream) {
                return stream.map(resources -> filteredResources(loader, resourcesType, resources, hidden, migrate));
            }

            return result;
        });
    }

    private static boolean isWorldData(String path) {
        for (String directory : worldRegistryDirectories(gameLoader)) {
            if (path.equals(directory) || path.startsWith(directory + "/")) {
                return true;
            }
        }

        return false;
    }

    private static Object filteredResources(ClassLoader loader, Class<?> resourcesType, Object resources,
            java.util.function.Predicate<String> hidden, boolean migrate) {
        return Proxy.newProxyInstance(loader, new Class<?>[] { resourcesType }, (proxy, method, arguments) -> {
            switch (method.getName()) {
                case "listResources" -> {
                    String path = String.valueOf(arguments[2]);

                    if (hidden.test(path)) {
                        return null;
                    }

                    if (migrate && isRecipe(path)) {
                        arguments = arguments.clone();
                        arguments[3] = migratingOutput(loader, arguments[3]);
                    }
                }
                case "getResource" -> {
                    Object id = arguments[1];
                    String path = (String) id.getClass().getMethod("getPath").invoke(id);

                    if (hidden.test(path)) {
                        return null;
                    }

                    if (migrate && isRecipe(path)) {
                        Object supplier = invoke(resources, method, arguments);
                        return supplier == null ? null : migrated(loader, supplier);
                    }
                }
                default -> {
                }
            }

            return invoke(resources, method, arguments);
        });
    }

    private static boolean isRecipe(String path) {
        return path.equals("recipe") || path.startsWith("recipe/");
    }

    /** The output a listing goes to, handed each recipe rewritten, and none whose conditions fail. */
    private static Object migratingOutput(ClassLoader loader, Object output) {
        Class<?> outputType = output.getClass().getInterfaces().length > 0 ? findInterface(output.getClass(),
                "net.minecraft.server.packs.PackResources$ResourceOutput") : null;

        if (outputType == null) {
            return output;
        }

        return Proxy.newProxyInstance(loader, new Class<?>[] { outputType }, (proxy, method, arguments) -> {
            if (method.getName().equals("accept") && arguments != null && arguments.length == 2) {
                Object supplier = migrated(loader, arguments[1]);

                if (supplier != null) {
                    invoke(output, method, new Object[] { arguments[0], supplier });
                }

                return null;
            }

            if (method.isDefault()) {
                return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, arguments);
            }

            return invoke(output, method, arguments);
        });
    }

    private static Class<?> findInterface(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                if (iface.getName().equals(name)) {
                    return iface;
                }
            }
        }

        return null;
    }

    /**
     * The recipe behind {@code supplier}, rewritten for this game, as a
     * supplier of its own; {@code null} when it is not to be loaded. A file
     * that is not JSON is handed on as it is, for the game to report.
     */
    private static Object migrated(ClassLoader loader, Object supplier) throws Throwable {
        byte[] bytes;

        Class<?> supplierType = type(loader, "net.minecraft.server.packs.resources.IoSupplier");

        try (java.io.InputStream in = (java.io.InputStream) invoke(supplier, supplierType.getMethod("get"), null)) {
            bytes = in.readAllBytes();
        }

        byte[] out;

        try {
            com.google.gson.JsonElement json = com.google.gson.JsonParser.parseString(
                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            com.google.gson.JsonElement rewritten = DataMigration.recipe(json, ModPacks::isInstalled);

            if (rewritten == null) {
                return null;
            }

            out = new com.google.gson.Gson().toJson(rewritten).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            out = bytes;
        }

        byte[] content = out;
        return Proxy.newProxyInstance(loader, new Class<?>[] { supplierType }, (proxy, method, arguments) -> {
            if (method.getName().equals("get")) {
                return new java.io.ByteArrayInputStream(content);
            }

            if (method.isDefault()) {
                return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, arguments);
            }

            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> "rewritten recipe";
            };
        });
    }

    private static boolean isInstalled(String id) {
        try {
            OctoRuntime runtime = OctoRuntime.get();
            return runtime.mods().stream().anyMatch(mod -> mod.id().equals(id)
                    || mod.metadata().provides().contains(id));
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try {
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static volatile Set<String> worldRegistryDirectories;

    /** Where the registries a world loads first keep their entries and tags: {@code worldgen/biome}, {@code enchantment}... */
    private static Set<String> worldRegistryDirectories(ClassLoader loader) {
        Set<String> known = worldRegistryDirectories;

        if (known != null) {
            return known;
        }

        Set<String> out = new LinkedHashSet<>();

        try {
            Class<?> dataLoader = type(loader, "net.minecraft.resources.RegistryDataLoader");
            Class<?> registries = type(loader, "net.minecraft.core.registries.Registries");
            Class<?> resourceKey = type(loader, "net.minecraft.resources.ResourceKey");
            java.lang.reflect.Method elements = registries.getMethod("elementsDirPath", resourceKey);
            // Their tags too: a tag naming an entry left out is a reference
            // nothing binds, which fails the world as surely.
            java.lang.reflect.Method tags = registries.getMethod("tagsDirPath", resourceKey);

            for (String list : List.of("WORLD_REGISTRIES", "DIMENSION_REGISTRIES")) {
                for (Object data : (List<?>) dataLoader.getField(list).get(null)) {
                    Object key = data.getClass().getMethod("key").invoke(data);
                    out.add((String) elements.invoke(null, key));
                    out.add((String) tags.invoke(null, key));
                }
            }
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.debug("the game's world registries could not be read: {}", e.toString());
        }

        worldRegistryDirectories = Set.copyOf(out);
        return worldRegistryDirectories;
    }

    private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object enumConstant(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }
}
