package studios.milkdromeda.octo.bridge.forge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Forge's registration: what puts a Forge mod's blocks, items and mobs in the game.
 *
 * <p>A Forge mod keeps its content in {@code DeferredRegister}s and hands each
 * one its mod bus; when Forge posts a registry's {@code RegisterEvent} there,
 * the register adds its entries. Octo ran none of that. A Forge mod was
 * constructed, its registers were made and handed a bus, and nothing ever asked
 * them for anything: Cracker's Wither Storm loaded with not one block, item or
 * mob of its own in the game.
 *
 * <p>Here every register a mod hands a bus is kept, and once the mods are
 * constructed each of the game's registries is opened, the registers for it
 * are asked for their entries through Forge's own {@code RegisterEvent} and
 * dispatcher, and the registries are frozen again. The entries go into the
 * game's own registries, which under Octo are the game's and not Forge's
 * wrappers around them; a registry only Forge has is filled as Forge's.
 */
public final class ForgeRegistration {
    private static final OctoLog LOG = OctoLog.of(ForgeRegistration.class);

    private static final String REGISTRIES = "net.minecraftforge.registries.";

    /** Every DeferredRegister a mod has handed a bus, in the order they were handed. */
    private static final List<Object> ATTACHED = new CopyOnWriteArrayList<>();

    /** Whether the registers have been asked already; one handed over after that is too late. */
    private static volatile boolean filled;

    private ForgeRegistration() {
    }

    /**
     * Called in place of {@code DeferredRegister.register(IEventBus)}, which
     * Forge has since replaced with a method taking its new bus group, and of
     * that method too.
     */
    public static void attach(Object deferredRegister, Object bus) {
        if (deferredRegister == null || ATTACHED.contains(deferredRegister)) {
            return;
        }

        ATTACHED.add(deferredRegister);

        if (filled) {
            LOG.warn("a Forge DeferredRegister for {} was handed its bus after registration ran; its entries are "
                    + "not in the game", describe(deferredRegister));
        }
    }

    /** Whether any Forge mod has content waiting to be registered. */
    public static boolean hasWork() {
        return !ATTACHED.isEmpty();
    }

    /**
     * Opens the registries, fills them and freezes them again, for a launch
     * where nothing else does: with NeoForge mods present, its registration
     * has the registries open already and calls {@link #fill} itself.
     */
    public static void run(ClassLoader loader) {
        if (!hasWork() || filled) {
            return;
        }

        List<Object> opened = new ArrayList<>();

        try {
            Object root = loader.loadClass("net.minecraft.core.registries.BuiltInRegistries").getField("REGISTRY")
                    .get(null);
            unfreeze(root, opened);

            for (Object registry : (Iterable<?>) root) {
                unfreeze(registry, opened);
            }
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("the registries could not be opened for Forge mods: {}", Failures.describe(Failures.unwrap(e)));
        }

        try {
            fill(loader);
        } finally {
            for (Object registry : opened) {
                try {
                    registry.getClass().getMethod("freeze").invoke(registry);
                } catch (Throwable e) {
                    Failures.rethrowIfFatal(e);
                    LOG.warn("could not freeze {}: {}", registry, Failures.describe(Failures.unwrap(e)));
                }
            }
        }

        StateIds.assign(loader);
    }

    /** Asks every register for its entries, registry by registry, in the game's own order. */
    public static void fill(ClassLoader loader) {
        if (!hasWork()) {
            return;
        }

        filled = true;

        try {
            Class<?> registerEvent = loader.loadClass(REGISTRIES + "RegisterEvent");
            Class<?> deferredType = loader.loadClass(REGISTRIES + "DeferredRegister");
            Class<?> forgeRegistryType = loader.loadClass(REGISTRIES + "ForgeRegistry");
            Class<?> registryType = loader.loadClass("net.minecraft.core.Registry");
            Class<?> keyType = loader.loadClass("net.minecraft.resources.ResourceKey");
            Class<?> idType = loader.loadClass("net.minecraft.resources.Identifier");

            Constructor<?> newEvent = registerEvent.getDeclaredConstructor(keyType, forgeRegistryType, registryType);
            newEvent.setAccessible(true);
            Class<?> objectType = loader.loadClass(REGISTRIES + "RegistryObject");
            Field entriesOf = deferredType.getDeclaredField("entries");
            entriesOf.setAccessible(true);
            Method idOf = objectType.getMethod("getId");
            Method update = objectType.getDeclaredMethod("updateReference", registerEvent);
            update.setAccessible(true);
            Method registerEntry = registerEvent.getMethod("register", keyType, idType, java.util.function.Supplier.class);
            Field keyOf = deferredType.getDeclaredField("registryKey");
            keyOf.setAccessible(true);
            Field modOf = deferredType.getDeclaredField("modid");
            modOf.setAccessible(true);
            Method identifier = keyType.getMethod("identifier");

            Object root = loader.loadClass("net.minecraft.core.registries.BuiltInRegistries").getField("REGISTRY")
                    .get(null);
            Method vanillaFor = registryType.getMethod("getValue", idType);

            // The game's registries in the order the game made them, which is
            // the order their content depends on each other in: blocks before
            // the items that place them. Then whatever only Forge has.
            Set<Object> keys = new LinkedHashSet<>();

            for (Object registry : (Iterable<?>) root) {
                keys.add(registryType.getMethod("key").invoke(registry));
            }

            for (Object register : ATTACHED) {
                keys.add(keyOf.get(register));
            }

            int registers = 0;
            int entries = 0;
            int failed = 0;

            for (Object key : keys) {
                List<Object> forKey = new ArrayList<>();

                for (Object register : ATTACHED) {
                    if (key.equals(keyOf.get(register))) {
                        forKey.add(register);
                    }
                }

                if (forKey.isEmpty()) {
                    continue;
                }

                Object vanilla = vanillaFor.invoke(root, identifier.invoke(key));
                Object forge = vanilla != null ? null : forgeRegistry(loader, identifier.invoke(key));

                if (vanilla == null && forge == null) {
                    // A registry only Forge keeps, which under Octo it never
                    // built: its entries are made and handed to whoever holds
                    // them, and the one kind the game has a table of its own
                    // for, entity data serializers, goes into that.
                    for (Object deferred : forKey) {
                        registers++;
                        int[] counts = detached(loader, deferred, key, identifier.invoke(key), entriesOf, idOf, objectType);
                        entries += counts[0];
                        failed += counts[1];
                    }

                    continue;
                }

                if (forge != null) {
                    quietly(forge, "unfreeze");
                }

                Object event = newEvent.newInstance(key, forge, vanilla);

                for (Object deferred : forKey) {
                    ForgeBuses.setActive(String.valueOf(modOf.get(deferred)));
                    registers++;

                    try {
                        // Entry by entry, as Forge's dispatcher does it, except
                        // that one entry failing costs that entry and not
                        // every one after it in the register.
                        for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) entriesOf.get(deferred))
                                .entrySet()) {
                            Object id = idOf.invoke(entry.getKey());

                            try {
                                // Its id while it is built: a block or an item
                                // takes its own from the registration it is part of.
                                studios.milkdromeda.octo.bridge.RegistrationContext.enter(key, id);
                                Object value;

                                try {
                                    value = ((java.util.function.Supplier<?>) entry.getValue()).get();
                                } finally {
                                    studios.milkdromeda.octo.bridge.RegistrationContext.exit();
                                }

                                if (value == null) {
                                    throw new IllegalStateException("its supplier made nothing");
                                }

                                registerEntry.invoke(event, key, id, (java.util.function.Supplier<Object>) () -> value);
                                update.invoke(entry.getKey(), event);
                                entries++;
                            } catch (Throwable e) {
                                Failures.rethrowIfFatal(e);
                                failed++;
                                Throwable cause = Failures.unwrap(e);
                                LOG.warn("{} could not be registered in {}: {}", id, identifier.invoke(key),
                                        Failures.describe(cause));
                                OctoLog.detail(cause);
                            }
                        }
                    } finally {
                        ForgeBuses.setActive(null);
                    }
                }

                if (forge != null) {
                    quietly(forge, "freeze");
                }
            }

            LOG.info("Forge registration ran: {} entr(ies) from {} register(s) are in the game{}", entries, registers,
                    failed == 0 ? "" : "; " + failed + " could not be registered");
            attributes(loader);
            clientContent(loader);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            Throwable cause = Failures.unwrap(e);
            LOG.warn("Forge registration could not run, so Forge mods' content is not in the game: {}",
                    Failures.describe(cause));
            OctoLog.detail(cause);
        }
    }

    /**
     * Asks Forge mods what attributes their mobs have, as Forge does once
     * entity types are registered. A mob nobody was asked about has none, and
     * cannot be made: "this.supplier is null" for every one Wither Storm adds.
     */
    private static void attributes(ClassLoader loader) {
        try {
            java.util.Map<Object, Object> given = new java.util.concurrent.ConcurrentHashMap<>();
            Object event = loader.loadClass("net.minecraftforge.event.entity.EntityAttributeCreationEvent")
                    .getConstructor(java.util.Map.class).newInstance(given);
            ForgeBuses.postToEveryMod(event);
            ModAttributes.addForge(given);
            LOG.info("{} mob(s) were given their attributes by Forge mods", given.size());
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            Throwable cause = Failures.unwrap(e);
            LOG.warn("Forge mods could not be asked for their mobs' attributes: {}", Failures.describe(cause));
            OctoLog.detail(cause);
        }
    }

    /**
     * On a client, asks Forge mods for their model layers and their entity and
     * block entity renderers, as Forge's client does before the first frame.
     * Nobody asked, and every mob a Forge mod added was there and not drawn.
     */
    private static void clientContent(ClassLoader loader) {
        if (!studios.milkdromeda.octo.runtime.OctoRuntime.isRunning()
                || studios.milkdromeda.octo.runtime.OctoRuntime.get().side() != studios.milkdromeda.octo.mod.Side.CLIENT) {
            return;
        }

        for (String event : new String[] { "RegisterLayerDefinitions", "RegisterRenderers" }) {
            try {
                ForgeBuses.postToEveryMod(loader.loadClass("net.minecraftforge.client.event.EntityRenderersEvent$"
                        + event).getConstructor().newInstance());
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                Throwable cause = Failures.unwrap(e);
                LOG.warn("Forge mods could not be asked for their {}: {}", event, Failures.describe(cause));
                OctoLog.detail(cause);
            }
        }
    }

    /** Builds the entries of a register whose registry does not exist, and binds each to its holder. */
    private static int[] detached(ClassLoader loader, Object deferred, Object key, Object registryId, Field entriesOf,
            Method idOf, Class<?> objectType) throws ReflectiveOperationException {
        Method bind = null;

        for (Method method : objectType.getDeclaredMethods()) {
            if (method.getName().equals("updateReference") && method.getParameterCount() == 2) {
                bind = method;
                bind.setAccessible(true);
            }
        }

        boolean serializers = String.valueOf(registryId).endsWith(":entity_data_serializers");
        Class<?> serializer = serializers ? loader.loadClass("net.minecraft.network.syncher.EntityDataSerializer") : null;
        Method registerSerializer = serializers
                ? loader.loadClass("net.minecraft.network.syncher.EntityDataSerializers")
                        .getMethod("registerSerializer", serializer)
                : null;
        int made = 0;
        int failed = 0;

        for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) entriesOf.get(deferred)).entrySet()) {
            Object id = idOf.invoke(entry.getKey());

            try {
                Object value = ((java.util.function.Supplier<?>) entry.getValue()).get();

                if (value == null) {
                    throw new IllegalStateException("its supplier made nothing");
                }

                if (registerSerializer != null) {
                    registerSerializer.invoke(null, value);
                }

                if (bind != null) {
                    bind.invoke(entry.getKey(), value, (java.util.function.Supplier<Object>) () -> null);
                }

                made++;
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                failed++;
                Throwable cause = Failures.unwrap(e);
                LOG.warn("{} could not be made for {}: {}", id, registryId, Failures.describe(cause));
                OctoLog.detail(cause);
            }
        }

        LOG.info("{} has no registry here; its {} entr(ies) were made and handed to the mod", registryId, made);
        return new int[] { made, failed };
    }

    /** A registry only Forge keeps, by its name; {@code null} when it keeps none of that name. */
    private static Object forgeRegistry(ClassLoader loader, Object id) {
        try {
            Class<?> manager = loader.loadClass(REGISTRIES + "RegistryManager");
            Object active = manager.getField("ACTIVE").get(null);

            for (Method method : manager.getMethods()) {
                if (method.getName().equals("getRegistry") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isInstance(id)) {
                    return method.invoke(active, id);
                }
            }
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.debug("Forge's registry {} could not be found: {}", id, Failures.describe(Failures.unwrap(e)));
        }

        return null;
    }

    /**
     * Opens one of the game's registries again: no longer frozen, its tags
     * unbound as the bootstrap left them, and for a registry whose entries
     * make their own holders, the map for those back.
     */
    private static void unfreeze(Object registry, List<Object> opened) throws ReflectiveOperationException {
        Class<?> mapped = registry.getClass();

        while (mapped != null && !mapped.getName().equals("net.minecraft.core.MappedRegistry")) {
            mapped = mapped.getSuperclass();
        }

        if (mapped == null) {
            return;
        }

        Field frozen = mapped.getDeclaredField("frozen");
        frozen.setAccessible(true);

        if (!frozen.getBoolean(registry)) {
            return;
        }

        frozen.setBoolean(registry, false);
        Field holders = mapped.getDeclaredField("unregisteredIntrusiveHolders");
        holders.setAccessible(true);

        if (holders.get(registry) == null && Registries.hasIntrusiveHolders(registry)) {
            holders.set(registry, new java.util.IdentityHashMap<>());
        }

        for (Class<?> nested : mapped.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("TagSet")) {
                Method unbound = nested.getDeclaredMethod("unbound");
                unbound.setAccessible(true);
                Field tags = mapped.getDeclaredField("allTags");
                tags.setAccessible(true);
                tags.set(registry, unbound.invoke(null));
            }
        }

        opened.add(registry);
    }

    private static void quietly(Object target, String method) {
        try {
            target.getClass().getMethod(method).invoke(target);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.debug("{}.{}() failed: {}", target.getClass().getSimpleName(), method,
                    Failures.describe(Failures.unwrap(e)));
        }
    }

    private static String describe(Object deferredRegister) {
        try {
            Field key = deferredRegister.getClass().getDeclaredField("registryKey");
            key.setAccessible(true);
            return String.valueOf(key.get(deferredRegister));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "a registry";
        }
    }
}
