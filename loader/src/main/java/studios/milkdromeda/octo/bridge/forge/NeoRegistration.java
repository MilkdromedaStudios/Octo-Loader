package studios.milkdromeda.octo.bridge.forge;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * NeoForge's registration, run once its mods are constructed.
 *
 * <p>FML does this between constructing mods and setting them up: NeoForge's
 * own registries are declared, every mod's new registries are created, the
 * game's registries are unfrozen, each registry's {@code RegisterEvent} is
 * posted — which is when every {@code DeferredRegister} puts its blocks and
 * items in — and the registries are frozen again. Without it, a NeoForge mod
 * constructed perfectly and none of its content was ever in the game: Create's
 * cogwheel was an unknown block. The steps are NeoForge's own, from its
 * library, called in the order it calls them; each is allowed to fail without
 * stopping the next, and what was unfrozen is always frozen again.
 */
public final class NeoRegistration {
    private static final OctoLog LOG = OctoLog.of(NeoRegistration.class);

    /** The id NeoForge's own listeners are registered under. */
    public static final String NEOFORGE = "neoforge";

    private static final String REGISTRIES = "net.neoforged.neoforge.registries.";

    private NeoRegistration() {
    }

    /** @return whether registration ran */
    public static boolean run(ClassLoader loader) {
        Class<?> gameData = find(loader, REGISTRIES + "GameData");

        if (gameData == null) {
            return false;
        }

        step("record the vanilla registries", () -> call(gameData, "vanillaSnapshot"));
        step("declare NeoForge's registries", () -> find(loader, REGISTRIES + "NeoForgeRegistriesSetup")
                .getMethod("setup", net.neoforged.bus.api.IEventBus.class)
                .invoke(null, ForgeBuses.neoBusFor(NEOFORGE)));
        step("register NeoForge's own content", () -> registerNeoForgeContent(loader));
        step("create the mods' registries", () -> call(find(loader, REGISTRIES + "RegistryManager"),
                "postNewRegistryEvent"));
        boolean unfrozen = step("unfreeze the registries", () -> call(gameData, "unfreezeData"));
        step("register the mods' content", () -> call(gameData, "postRegisterEvents"));
        studios.milkdromeda.octo.bridge.RegistrationContext.clear();

        // Frozen the way the game's bootstrap freezes them, not the way
        // NeoForge's patched game does: NeoForge binds every registry's tags
        // first, which this game's freeze refuses.
        if (unfrozen) {
            step("freeze the registries", () -> freezeAll(loader));
            step("bake the registries", () -> bakeAll(loader));
        }

        StateIds.assign(loader);

        // Capabilities next, as NeoForge registers them once the registries
        // are frozen and before setup: each mod says what its blocks, block
        // entities, entities and items offer — Create's tanks a fluid
        // handler, its depots an item handler — and every query for one
        // finds nothing if this never ran.
        step("register the mods' capabilities", () -> call(find(loader,
                "net.neoforged.neoforge.capabilities.CapabilityHooks"), "init"));

        // And their packets: without them in NeoForge's table, the first
        // packet a mod sends cannot be written and drops the player.
        ModPayloads.setup(loader);

        // And on a client, their entity renderers: Registrate, and most mods,
        // register them when NeoForge asks, and nothing asked. Create's
        // contraptions — everything a bearing turns — had none, and the
        // first to come into view ended the game.
        if (studios.milkdromeda.octo.runtime.OctoRuntime.isRunning()
                && studios.milkdromeda.octo.runtime.OctoRuntime.get().side() == studios.milkdromeda.octo.mod.Side.CLIENT) {
            step("register the mods' entity renderers", () -> net.neoforged.fml.ModLoader.postEventWrapContainerInModOrder(
                    (net.neoforged.bus.api.Event) find(loader,
                            "net.neoforged.neoforge.client.event.EntityRenderersEvent$RegisterRenderers")
                            .getConstructor().newInstance()));

            // And their menus' screens: NeoForge's patched MenuScreens asks for them
            // with the game's own table, which this hands over. Without it every
            // mod menu opened to nothing: "no screen is registered for the menu".
            step("register the mods' menu screens", () -> {
                Class<?> screens = find(loader, "net.minecraft.client.gui.screens.MenuScreens");
                java.lang.reflect.Field table = screens.getDeclaredField("SCREENS");
                table.setAccessible(true);
                Object map = table.get(null);
                net.neoforged.fml.ModLoader.postEventWrapContainerInModOrder((net.neoforged.bus.api.Event) find(loader,
                        "net.neoforged.neoforge.client.event.RegisterMenuScreensEvent")
                        .getConstructor(java.util.Map.class).newInstance(map));
                LOG.info("{} menu screen(s) registered", ((java.util.Map<?, ?>) map).size());
            });
        }

        LOG.info("NeoForge registration ran: {}", summary(loader));
        return true;
    }

    /**
     * What NeoForge itself registers from its mod class: the fluid types of
     * water, lava and empty, its attributes, ingredient and condition types.
     * NeoForge's mod is not constructed here, so its registers are handed
     * the bus directly; without them every holder for one stayed unbound,
     * and Create's setup stopped at the first — lava's fluid type — with
     * everything it registers after that never registered.
     */
    private static void registerNeoForgeContent(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> mod = find(loader, "net.neoforged.neoforge.common.NeoForgeMod");
        Class<?> register = find(loader, REGISTRIES + "DeferredRegister");

        if (mod == null || register == null) {
            return;
        }

        java.lang.reflect.Method onBus = register.getMethod("register", net.neoforged.bus.api.IEventBus.class);
        net.neoforged.bus.api.IEventBus bus = ForgeBuses.neoBusFor(NEOFORGE);
        int registered = 0;

        for (java.lang.reflect.Field field : mod.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !register.isAssignableFrom(field.getType())) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object deferred = field.get(null);

                if (deferred != null) {
                    onBus.invoke(deferred, bus);
                    registered++;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOG.warn("NeoForge's {} could not be registered: {}", field.getName(),
                        Failures.describe(Failures.unwrap(e)));
            }
        }

        LOG.debug("{} of NeoForge's own registers handed the bus", registered);
    }

    /** How many blocks and items each mod has in the game now: what registration actually achieved. */
    private static String summary(ClassLoader loader) {
        try {
            Class<?> builtIn = find(loader, "net.minecraft.core.registries.BuiltInRegistries");
            java.util.Map<String, int[]> counts = new java.util.TreeMap<>();
            String[] registries = { "BLOCK", "ITEM" };

            for (int i = 0; i < registries.length; i++) {
                Object registry = builtIn.getField(registries[i]).get(null);

                for (Object id : (Iterable<?>) registry.getClass().getMethod("keySet").invoke(registry)) {
                    String namespace = (String) id.getClass().getMethod("getNamespace").invoke(id);

                    if (!namespace.equals("minecraft")) {
                        counts.computeIfAbsent(namespace, ignored -> new int[2])[i]++;
                    }
                }
            }

            if (counts.isEmpty()) {
                return "no mod added a block or an item";
            }

            java.util.List<String> parts = new java.util.ArrayList<>();
            counts.forEach((namespace, count) -> parts.add(namespace + " " + count[0] + " block(s), " + count[1]
                    + " item(s)"));
            return String.join("; ", parts);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            return "the registries could not be read back";
        }
    }

    /** The registry of registries first, then each registry in it, each on its own. */
    private static void freezeAll(ClassLoader loader) throws Exception {
        Class<?> builtIn = find(loader, "net.minecraft.core.registries.BuiltInRegistries");
        Object root = builtIn.getField("REGISTRY").get(null);
        java.lang.reflect.Method freeze = find(loader, "net.minecraft.core.Registry").getMethod("freeze");
        freeze.invoke(root);
        int failed = 0;

        for (Object registry : (Iterable<?>) root) {
            try {
                freeze.invoke(registry);
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                failed++;
                LOG.warn("could not freeze {}: {}", registry, Failures.describe(Failures.unwrap(e)));
            }
        }

        if (failed > 0) {
            LOG.warn("{} registr(ies) stayed unfrozen", failed);
        }
    }

    /**
     * Runs each registry's bake callbacks, as NeoForge's patched freeze does.
     * Mods build their look-up tables from a finished registry there: Create
     * sorts its fan processing types when its registry is baked, and with
     * nothing baked no fan washed, smelted, smoked or haunted anything.
     * The game's own registries are left as they were.
     */
    private static void bakeAll(ClassLoader loader) throws Exception {
        Class<?> base = find(loader, REGISTRIES + "BaseMappedRegistry");
        Class<?> callback = find(loader, REGISTRIES + "callback.BakeCallback");

        if (base == null || callback == null) {
            return;
        }

        java.lang.reflect.Field callbacks = base.getDeclaredField("bakeCallbacks");
        callbacks.setAccessible(true);
        java.lang.reflect.Method onBake = callback.getMethod("onBake", find(loader, "net.minecraft.core.Registry"));
        Object root = find(loader, "net.minecraft.core.registries.BuiltInRegistries").getField("REGISTRY").get(null);
        int baked = 0;

        for (Object registry : (Iterable<?>) root) {
            // The game's own registries were never baked here, and Octo gives
            // block states their ids itself: only the mods' registries are.
            if (!base.isInstance(registry) || String.valueOf(registry.getClass().getMethod("key").invoke(registry))
                    .contains("minecraft:root / minecraft:")) {
                continue;
            }

            for (Object each : new java.util.ArrayList<>((java.util.List<?>) callbacks.get(registry))) {
                try {
                    onBake.invoke(each, registry);
                    baked++;
                } catch (Throwable e) {
                    Failures.rethrowIfFatal(e);
                    LOG.warn("baking {} failed: {}", registry, Failures.describe(Failures.unwrap(e)));
                }
            }
        }

        LOG.debug("{} registry bake callback(s) run", baked);
    }

    private interface Step {
        void run() throws Exception;
    }

    private static boolean step(String what, Step step) {
        try {
            step.run();
            return true;
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            Throwable cause = Failures.unwrap(e);
            LOG.warn("NeoForge registration could not {}: {}", what, Failures.describe(cause));
            OctoLog.detail(cause);
            return false;
        }
    }

    private static void call(Class<?> owner, String method) throws Exception {
        owner.getMethod(method).invoke(null);
    }

    private static Class<?> find(ClassLoader loader, String name) {
        try {
            return Class.forName(name, true, loader);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            return null;
        }
    }
}
