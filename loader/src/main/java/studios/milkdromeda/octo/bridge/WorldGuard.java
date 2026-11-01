package studios.milkdromeda.octo.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import studios.milkdromeda.octo.common.WorldBackups;
import studios.milkdromeda.octo.common.WorldContent;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Keeps worlds whole when their mods change.
 *
 * <p>A block is saved by its mod's name for it. When the mod is missing — it
 * failed to build, it was removed, it renamed the block — the game reads the
 * name, does not know it, puts air in its place, and saves the air: the blocks
 * are gone for good, and with them every chest's items and every entity of
 * that mod. Octo stops that three ways:
 *
 * <ol>
 * <li>Each world records what it was last opened with
 * ({@link WorldContent}). Opening it with something missing backs the world
 * up first, before a single chunk is read; opening it with the mods changed
 * does too, unless turned off.</li>
 * <li>Each chunk and each chunk of entities is looked at as it is read. One
 * with a block, block entity, entity or item the game does not know is kept
 * as it is on disk: it is not saved over while the content is missing, so
 * putting the mod back brings everything back.</li>
 * <li>The content still missing stays in the world's record, so the world
 * goes on being protected each time it is opened until the mod returns or
 * the player accepts it is gone ({@link WorldContent#accept}).</li>
 * </ol>
 *
 * <p>Switches: {@code -Docto.worldGuard=false} turns it all off;
 * {@code -Docto.backups.onModChange=false} keeps backups to missing content
 * only; {@code -Docto.backups.keep=N} keeps the newest N automatic backups of
 * each world (10).
 */
public final class WorldGuard {
    private static final OctoLog LOG = OctoLog.of(WorldGuard.class);

    private static final String[][] REGISTRIES = {
            { "block", "BLOCK" }, { "item", "ITEM" }, { "entity_type", "ENTITY_TYPE" },
            { "block_entity_type", "BLOCK_ENTITY_TYPE" } };

    /** Per storage (level, dimension, kind), the chunks kept as they are on disk. */
    private static final Map<String, Set<Long>> PROTECTED = new ConcurrentHashMap<>();
    /** Per registry, whether the game knows an id. */
    private static final Map<String, Map<String, Boolean>> KNOWN = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED_MISSING = ConcurrentHashMap.newKeySet();

    private static volatile Object[] registries;
    /** The game's class loader, from the storage the first chunk was read from. */
    private static volatile ClassLoader gameLoader;
    private static volatile Method tryParse;
    private static volatile Method containsKey;

    private WorldGuard() {
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("octo.worldGuard"));
    }

    // ------------------------------------------------------------- the world

    /** {@code MinecraftServer.loadLevel()}, before any level is made: the world's record checked, and backed up. */
    public static void opening(Object server) {
        if (!enabled() || server == null) {
            return;
        }

        PROTECTED.clear();
        REPORTED_MISSING.clear();

        try {
            Path world = worldOf(server);

            if (world == null) {
                return;
            }

            WorldContent now = current(server.getClass().getClassLoader());
            WorldContent before = WorldContent.read(world);
            int keep = Integer.getInteger("octo.backups.keep", 10);
            // Chunks under region/ until 26.1, under dimensions/ since.
            boolean existing = Files.isDirectory(world.resolve("region"))
                    || Files.isDirectory(world.resolve("dimensions"));

            if (before == null) {
                // First opened with Octo: nothing to compare with, but a world
                // that already has chunks is worth one copy as it was.
                if (existing) {
                    backup(world, "first-octo-open", keep);
                }

                now.write(world);
                return;
            }

            WorldContent.Difference difference = before.compareTo(now, WorldContent.accepted(world));

            if (difference.contentMissing()) {
                LOG.warn("world {} has {} block(s), item(s) and entities from {} that are not in the game now; "
                        + "it is backed up, and chunks holding them are kept as they are until the mod is back",
                        world.getFileName(), difference.missingCount(), difference.missingMods());
                backup(world, WorldBackups.MISSING_CONTENT, keep);
                report(world, difference);
            } else if (difference.modsChanged() && !"false".equalsIgnoreCase(
                    System.getProperty("octo.backups.onModChange"))) {
                LOG.info("world {}: mods changed since it was last opened (added {}, removed {}, updated {}); "
                        + "backing it up first", world.getFileName(), difference.added(), difference.removed(),
                        difference.updated().keySet());
                backup(world, WorldBackups.MODS_CHANGED, keep);
            }

            if (!difference.contentMissing()) {
                Files.deleteIfExists(world.resolve("octo/guard-report.txt"));
            }

            now.carryingOver(difference).write(world);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("the world's mods could not be checked before it opened: {}", Failures.describe(e));
            OctoLog.detail(e);
        }
    }

    private static void backup(Path world, String reason, int keep) {
        long started = System.nanoTime();

        try {
            WorldBackups.Backup made = WorldBackups.create(world, reason);
            LOG.info("backed up world {} to {} ({} MB, {} ms)", world.getFileName(), made.file(),
                    made.size() / (1024 * 1024), (System.nanoTime() - started) / 1_000_000);
            WorldBackups.prune(WorldBackups.folderFor(world), keep);
        } catch (Exception e) {
            LOG.warn("world {} could not be backed up: {}", world.getFileName(), Failures.describe(e));
            OctoLog.detail(e);
        }
    }

    private static void report(Path world, WorldContent.Difference difference) {
        List<String> lines = new ArrayList<>();
        lines.add("Octo Loader world guard - " + Instant.now());
        lines.add("");
        lines.add("This world uses content from mods the game does not have now: " + difference.missingMods());
        lines.add("The world was backed up before it opened. Chunks and entities that use this content are not");
        lines.add("saved while it is missing, so they come back as they were when the mod is back. Changes made");
        lines.add("inside those chunks meanwhile are not kept.");
        lines.add("");
        lines.add("If a mod was removed on purpose, accept its removal in Octo Launcher (or add its id to");
        lines.add(WorldContent.ACCEPTED_FILE + "), and its blocks will be dropped the next time those chunks save.");
        lines.add("");
        difference.missing().forEach((registry, ids) -> {
            lines.add(registry + ":");
            ids.forEach(id -> lines.add("  " + id));
        });

        try {
            Files.createDirectories(world.resolve("octo"));
            Files.write(world.resolve("octo/guard-report.txt"), lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // The log has said it.
        }
    }

    private static Path worldOf(Object server) throws ReflectiveOperationException {
        Object access = field(server, "storageSource");

        if (access == null) {
            return null;
        }

        ClassLoader loader = server.getClass().getClassLoader();
        Class<?> resource = Class.forName("net.minecraft.world.level.storage.LevelResource", true, loader);
        Object root = resource.getField("ROOT").get(null);
        Path path = (Path) access.getClass().getMethod("getLevelPath", resource).invoke(access, root);
        return path.toAbsolutePath().normalize();
    }

    /** The game's mods and the ids they added, as a record to compare with. */
    static WorldContent current(ClassLoader loader) throws ReflectiveOperationException {
        Map<String, String> mods = new TreeMap<>();

        if (OctoRuntime.isRunning()) {
            for (LoadedMod mod : OctoRuntime.get().mods()) {
                if (!mod.isBuiltin() && !mod.isFailed()) {
                    mods.put(mod.id(), String.valueOf(mod.metadata().version()));
                }
            }
        }

        Map<String, Collection<String>> ids = new TreeMap<>();
        Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader);

        for (String[] registry : REGISTRIES) {
            Object instance = builtIn.getField(registry[1]).get(null);
            Collection<?> keys = (Collection<?>) instance.getClass().getMethod("keySet").invoke(instance);
            List<String> modded = new ArrayList<>();

            for (Object key : keys) {
                String id = String.valueOf(key);

                if (!WorldContent.namespace(id).equals("minecraft")) {
                    modded.add(id);
                }
            }

            ids.put(registry[0], modded);
        }

        return new WorldContent(mods, ids);
    }

    // ------------------------------------------------------------ the chunks

    /**
     * {@code SimpleRegionStorage.read(pos)}: what was read, looked at once it
     * is, and the chunk kept as it is on disk if it holds what the game does
     * not know.
     */
    public static Object afterRead(Object storage, Object pos, Object future) {
        if (!enabled() || !(future instanceof CompletableFuture<?> read)) {
            return future;
        }

        return read.thenApply(result -> {
            try {
                if (result instanceof Optional<?> optional && optional.isPresent()) {
                    inspect(storage, pos, optional.get());
                }
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                // Never in the way of reading a chunk.
            }

            return result;
        });
    }

    /** {@code SimpleRegionStorage.write(pos, ...)}: whether to leave the chunk on disk as it is. */
    public static boolean keepOnDisk(Object storage, Object pos) {
        if (PROTECTED.isEmpty()) {
            return false;
        }

        try {
            Set<Long> chunks = PROTECTED.get(storageKey(storage));
            return chunks != null && chunks.contains(pack(pos));
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            return false;
        }
    }

    private static void inspect(Object storage, Object pos, Object tag) throws ReflectiveOperationException {
        if (gameLoader == null) {
            gameLoader = storage.getClass().getClassLoader();
        }

        String key = storageKey(storage);
        String kind = key.substring(key.lastIndexOf('|') + 1);
        List<String> unknown = new ArrayList<>(2);

        switch (kind) {
            case "chunk" -> {
                for (Object section : list(tag, "sections")) {
                    Object states = get(section, "block_states");

                    for (Object entry : list(states, "palette")) {
                        check("block", string(get(entry, "Name")), unknown);
                    }
                }

                for (Object blockEntity : list(tag, "block_entities")) {
                    check("block_entity_type", string(get(blockEntity, "id")), unknown);
                    items(blockEntity, unknown, 0);
                }
            }
            case "entities" -> {
                for (Object entity : list(tag, "Entities")) {
                    entity(entity, unknown, 0);
                }
            }
            default -> {
                return;
            }
        }

        if (!unknown.isEmpty()) {
            long packed = pack(pos);

            if (PROTECTED.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(packed)) {
                String first = unknown.get(0);

                if (REPORTED_MISSING.add(WorldContent.namespace(first))) {
                    LOG.warn("{} holds {} from {}, which the game does not have now; it and every chunk like it "
                            + "are kept on disk as they are until the mod is back", describe(key, packed), first,
                            WorldContent.namespace(first));
                }
            }
        }
    }

    private static void entity(Object entity, List<String> unknown, int depth) throws ReflectiveOperationException {
        if (depth > 16) {
            return;
        }

        check("entity_type", string(get(entity, "id")), unknown);
        items(entity, unknown, 0);

        for (Object passenger : list(entity, "Passengers")) {
            entity(passenger, unknown, depth + 1);
        }
    }

    /** Every item stack inside, at any depth: a compound with an id and a count. */
    private static void items(Object tag, List<String> unknown, int depth) throws ReflectiveOperationException {
        if (tag == null || depth > 24 || !unknown.isEmpty()) {
            return;
        }

        if (tag instanceof List<?> list) {
            for (Object element : list) {
                items(element, unknown, depth + 1);
            }

            return;
        }

        if (!tag.getClass().getName().equals("net.minecraft.nbt.CompoundTag")) {
            return;
        }

        Object id = get(tag, "id");

        if (id != null && depth > 0 && (get(tag, "count") != null || get(tag, "Count") != null)) {
            check("item", string(id), unknown);
        }

        for (Object name : (Set<?>) call(tag, "keySet")) {
            Object value = get(tag, (String) name);

            if (value instanceof List<?> || value != null
                    && value.getClass().getName().equals("net.minecraft.nbt.CompoundTag")) {
                items(value, unknown, depth + 1);
            }
        }
    }

    private static void check(String registry, String id, List<String> unknown) throws ReflectiveOperationException {
        if (id == null || id.indexOf(':') < 0 || id.startsWith("minecraft:")) {
            return;
        }

        if (!known(registry, id)) {
            unknown.add(id);
        }
    }

    private static boolean known(String registry, String id) {
        return KNOWN.computeIfAbsent(registry, ignored -> new ConcurrentHashMap<>()).computeIfAbsent(id, value -> {
            try {
                Object[] all = registries();
                Object registryInstance = null;

                for (int i = 0; i < REGISTRIES.length; i++) {
                    if (REGISTRIES[i][0].equals(registry)) {
                        registryInstance = all[i];
                    }
                }

                Object parsed = tryParse.invoke(null, value);
                return parsed == null || (boolean) containsKey.invoke(registryInstance, parsed);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Unsure is known: never keep a chunk back on a guess.
                return true;
            }
        });
    }

    private static Object[] registries() throws ReflectiveOperationException {
        Object[] all = registries;

        if (all == null) {
            ClassLoader loader = gameLoader;
            Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader);

            all = new Object[REGISTRIES.length];

            for (int i = 0; i < REGISTRIES.length; i++) {
                all[i] = builtIn.getField(REGISTRIES[i][1]).get(null);
            }

            Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", true, builtIn.getClassLoader());
            tryParse = identifier.getMethod("tryParse", String.class);
            containsKey = Class.forName("net.minecraft.core.Registry", true, builtIn.getClassLoader())
                    .getMethod("containsKey", identifier);
            registries = all;
        }

        return all;
    }

    // ---------------------------------------------------------------- helpers

    private static final Map<Class<?>, Method> STORAGE_INFO = new ConcurrentHashMap<>();

    private static String storageKey(Object storage) throws ReflectiveOperationException {
        Method method = STORAGE_INFO.computeIfAbsent(storage.getClass(), type -> {
            try {
                return type.getMethod("storageInfo");
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        });
        Object info = method.invoke(storage);
        return call(info, "level") + "|" + call(info, "dimension") + "|" + call(info, "type");
    }

    private static String describe(String key, long packed) {
        String[] parts = key.split("\\|");
        int x = (int) packed;
        int z = (int) (packed >>> 32);
        return (parts[2].equals("entities") ? "the entities of chunk " : "chunk ") + x + ", " + z + " of "
                + parts[1];
    }

    private static long pack(Object pos) throws ReflectiveOperationException {
        return (long) call(pos, "pack");
    }

    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = METHODS.computeIfAbsent(target.getClass().getName() + "#" + name, ignored -> {
            try {
                Method found = target.getClass().getMethod(name);
                found.setAccessible(true);
                return found;
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        });
        return method.invoke(target);
    }

    private static Object get(Object compound, String key) throws ReflectiveOperationException {
        if (compound == null || !compound.getClass().getName().equals("net.minecraft.nbt.CompoundTag")) {
            return null;
        }

        Method method = METHODS.computeIfAbsent("CompoundTag#get", ignored -> {
            try {
                return compound.getClass().getMethod("get", String.class);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        });
        return method.invoke(compound, key);
    }

    private static List<?> list(Object compound, String key) throws ReflectiveOperationException {
        Object value = get(compound, key);
        return value instanceof List<?> list ? list : List.of();
    }

    private static String string(Object tag) throws ReflectiveOperationException {
        return tag != null && tag.getClass().getName().equals("net.minecraft.nbt.StringTag")
                ? (String) call(tag, "value") : null;
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException e) {
                // Declared higher up.
            }
        }

        return null;
    }
}
