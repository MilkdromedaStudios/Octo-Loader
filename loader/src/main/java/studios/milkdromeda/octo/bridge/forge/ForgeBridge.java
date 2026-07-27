package studios.milkdromeda.octo.bridge.forge;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import studios.milkdromeda.octo.bridge.LoaderBridge;
import studios.milkdromeda.octo.mod.EntrypointSpec;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.mod.format.MetadataParser.Phases;
import studios.milkdromeda.octo.runtime.Lifecycle;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Runs Forge and NeoForge mods, of every generation.
 *
 * <p>Four construction conventions have to be supported, because mods in the
 * wild use all of them:
 *
 * <ul>
 *   <li>1.7–1.12: a no-argument constructor, with lifecycle methods marked
 *       {@code @Mod.EventHandler} taking {@code FMLPreInitializationEvent} and friends.</li>
 *   <li>1.13–1.19: a no-argument constructor that fetches its bus from
 *       {@code FMLJavaModLoadingContext}.</li>
 *   <li>1.20 Forge and NeoForge: a constructor taking the mod's {@code IEventBus}.</li>
 *   <li>Pre-{@code mcmod.info}: a {@code mod_Something} class with a {@code load()} method.</li>
 * </ul>
 */
public final class ForgeBridge implements LoaderBridge {
    private static final OctoLog LOG = OctoLog.of(ForgeBridge.class);

    private static final String EVENT_HANDLER = "EventHandler";

    @Override
    public String name() {
        return "forge";
    }

    @Override
    public boolean supports(ModFormat format) {
        return format.family() == ModFormat.Family.FORGE;
    }

    @Override
    public void construct(LoadedMod mod, ClassLoader classLoader) {
        List<EntrypointSpec> specs = mod.metadata().entrypoints(Phases.MOD_CLASS);

        if (specs.isEmpty()) {
            LOG.warn("{}: no @Mod class was found in the jar; nothing to run", mod.id());
            mod.recordFix("no entrypoint class found");
            return;
        }

        for (EntrypointSpec spec : specs) {
            ForgeBuses.setActive(mod.id());

            try {
                Class<?> type = Class.forName(spec.className(), false, classLoader);
                Object instance = instantiate(type, mod);
                OctoRuntime.get().registerEntrypoint(Phases.MOD_CLASS, mod, instance);
                LOG.debug("{}: constructed {}", mod.id(), spec.className());
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                Throwable cause = Failures.unwrap(e);
                LOG.error("{}: could not construct {}: {}", mod.id(), spec.className(), Failures.describe(cause));
                OctoLog.detail(cause);
                mod.fail(cause);
            } finally {
                ForgeBuses.setActive(null);
            }
        }
    }

    /** Picks the constructor the mod's generation expects. */
    private Object instantiate(Class<?> type, LoadedMod mod) throws ReflectiveOperationException {
        Constructor<?> best = null;
        Object[] arguments = null;

        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();

            if (parameters.length == 0) {
                if (best == null) {
                    best = constructor;
                    arguments = new Object[0];
                }

                continue;
            }

            Object[] resolved = resolveArguments(parameters, mod);

            if (resolved != null) {
                // A constructor asking for the bus is the newer convention and is
                // preferred: it is how NeoForge mods expect to be built.
                best = constructor;
                arguments = resolved;
                break;
            }
        }

        if (best == null) {
            throw new NoSuchMethodException(type.getName() + " has no constructor Octo can call");
        }

        best.setAccessible(true);
        return best.newInstance(arguments);
    }

    /** @return the arguments to pass, or {@code null} when a parameter cannot be supplied */
    private Object[] resolveArguments(Class<?>[] parameters, LoadedMod mod) {
        Object[] arguments = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            String name = parameters[i].getName();

            arguments[i] = switch (name) {
                case "net.neoforged.bus.api.IEventBus" -> ForgeBuses.neoBusFor(mod.id());
                case "net.minecraftforge.eventbus.api.IEventBus" -> ForgeBuses.forgeBusFor(mod.id());
                case "net.neoforged.fml.ModContainer" -> ForgeBuses.neoContainerFor(mod.id());
                case "net.minecraftforge.fml.ModContainer" -> ForgeBuses.forgeContainerFor(mod.id());
                case "net.neoforged.api.distmarker.Dist" ->
                        OctoRuntime.get().side() == Side.CLIENT
                                ? net.neoforged.api.distmarker.Dist.CLIENT
                                : net.neoforged.api.distmarker.Dist.DEDICATED_SERVER;
                case "net.minecraftforge.api.distmarker.Dist" ->
                        OctoRuntime.get().side() == Side.CLIENT
                                ? net.minecraftforge.api.distmarker.Dist.CLIENT
                                : net.minecraftforge.api.distmarker.Dist.DEDICATED_SERVER;
                default -> MISSING;
            };

            if (arguments[i] == MISSING) {
                return null;
            }
        }

        return arguments;
    }

    private static final Object MISSING = new Object();

    @Override
    public void dispatch(Lifecycle phase, LoadedMod mod) {
        for (Object instance : List.copyOf(mod.instances())) {
            dispatchLegacy(phase, mod, instance);
            dispatchModLoader(phase, mod, instance);
        }

        String eventName = modernEventFor(phase, mod);

        if (eventName == null) {
            return;
        }

        postModernEvent(mod, eventName);

        if (phase == Lifecycle.INTER_MOD) {
            postModernEvent(mod, "InterModProcessEvent");
        }
    }

    private String modernEventFor(Lifecycle phase, LoadedMod mod) {
        boolean sided = OctoRuntime.get().side() == Side.CLIENT;

        return switch (phase) {
            case CONSTRUCT -> "FMLConstructModEvent";
            case COMMON_SETUP -> "FMLCommonSetupEvent";
            case SIDED_SETUP -> sided ? "FMLClientSetupEvent" : "FMLDedicatedServerSetupEvent";
            case INTER_MOD -> "InterModEnqueueEvent";
            case LOAD_COMPLETE -> "FMLLoadCompleteEvent";
            case PRE_LAUNCH -> null;
        };
    }

    /** Posts one of the 1.13-and-later lifecycle events on the mod's own bus. */
    private void postModernEvent(LoadedMod mod, String simpleName) {
        boolean neoForge = mod.format() == ModFormat.NEOFORGE;
        String packageName = neoForge ? "net.neoforged.fml.event.lifecycle." : "net.minecraftforge.fml.event.lifecycle.";

        try {
            Class<?> type = Class.forName(packageName + simpleName, true, getClass().getClassLoader());
            Object event = type.getConstructor(String.class).newInstance(mod.id());

            if (neoForge) {
                ForgeBuses.neoBusFor(mod.id()).post((net.neoforged.bus.api.Event) event);
            } else {
                ForgeBuses.forgeBusFor(mod.id()).post((net.minecraftforge.eventbus.api.Event) event);
            }
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            Throwable cause = Failures.unwrap(e);
            LOG.warn("{}: could not post {}: {}", mod.id(), simpleName, Failures.describe(cause));
            OctoLog.detail(cause);
        }
    }

    /**
     * Calls {@code @Mod.EventHandler} methods, the 1.7-through-1.12 convention.
     *
     * <p>Both the {@code cpw.mods.fml} and {@code net.minecraftforge.fml} spellings
     * are accepted, and the event object is built from whichever class the method
     * asks for, so a mod compiled against either package works untouched.
     */
    private void dispatchLegacy(Lifecycle phase, LoadedMod mod, Object instance) {
        String wanted = switch (phase) {
            case COMMON_SETUP -> "FMLPreInitializationEvent";
            case SIDED_SETUP -> "FMLInitializationEvent";
            case LOAD_COMPLETE -> "FMLPostInitializationEvent";
            default -> null;
        };

        if (wanted == null) {
            return;
        }

        for (Method method : instance.getClass().getDeclaredMethods()) {
            if (method.getParameterCount() != 1 || !hasEventHandlerAnnotation(method)) {
                continue;
            }

            Class<?> parameter = method.getParameterTypes()[0];

            if (!parameter.getSimpleName().equals(wanted)) {
                continue;
            }

            try {
                Object event = parameter.getConstructor(String.class).newInstance(mod.id());
                method.setAccessible(true);
                method.invoke(instance, event);
                LOG.debug("{}: called {}({})", mod.id(), method.getName(), wanted);
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                Throwable cause = Failures.unwrap(e);
                LOG.error("{}: {} threw {}", mod.id(), method.getName(), Failures.describe(cause));
                OctoLog.detail(cause);
                mod.fail(cause);
            }
        }
    }

    private static boolean hasEventHandlerAnnotation(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (annotation.annotationType().getSimpleName().equals(EVENT_HANDLER)) {
                return true;
            }
        }

        return false;
    }

    /** Calls {@code load()} on a ModLoader-era {@code mod_*} class. */
    private void dispatchModLoader(Lifecycle phase, LoadedMod mod, Object instance) {
        if (phase != Lifecycle.COMMON_SETUP || !isModLoaderClass(instance.getClass())) {
            return;
        }

        try {
            Method load = instance.getClass().getMethod("load");

            if (!Modifier.isStatic(load.getModifiers())) {
                load.setAccessible(true);
                load.invoke(instance);
                LOG.info("{}: called ModLoader-era load()", mod.id());
            }
        } catch (NoSuchMethodException e) {
            LOG.debug("{}: no load() method", mod.id());
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            Throwable cause = Failures.unwrap(e);
            LOG.error("{}: load() threw {}", mod.id(), Failures.describe(cause));
            OctoLog.detail(cause);
            mod.fail(cause);
        }
    }

    private static boolean isModLoaderClass(Class<?> type) {
        return type.getSimpleName().startsWith("mod_")
                || (type.getSuperclass() != null && type.getSuperclass().getSimpleName().equals("BaseMod"));
    }
}
