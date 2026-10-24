package studios.milkdromeda.octo.bridge.forge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import net.neoforged.fml.loading.modscan.ModAnnotation;

import studios.milkdromeda.octo.mod.BytecodeIndex.ScannedAnnotation;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * What a Forge or NeoForge mod's annotations ask the loader to do with its classes.
 *
 * <p>Two things, both read from the bytecode index so that nothing is loaded to
 * find out: whether a class belongs on this side at all ({@code @Mod(dist =
 * Dist.CLIENT)}), and which classes hold static listeners the loader is meant to
 * subscribe on the mod's behalf ({@code @EventBusSubscriber}). The second is how
 * a large share of mods hook their events — without it they construct, and then
 * nothing they wrote ever runs.
 */
public final class AnnotatedClasses {
    private static final OctoLog LOG = OctoLog.of(AnnotatedClasses.class);

    private static final Set<String> SUBSCRIBERS = Set.of(
            "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;",
            "Lnet/neoforged/fml/common/EventBusSubscriber;",
            "Lnet/neoforged/fml/common/Mod$EventBusSubscriber;");

    private static final String EB7_SUBSCRIBE = "net.minecraftforge.eventbus.api.listener.SubscribeEvent";
    private static final String FORGE_SUBSCRIBE = "net.minecraftforge.eventbus.api.SubscribeEvent";
    private static final String NEO_SUBSCRIBE = "net.neoforged.bus.api.SubscribeEvent";

    private AnnotatedClasses() {
    }

    /**
     * Whether a class annotated with one of {@code descriptors} is for this side.
     *
     * <p>{@code @Mod} says it with {@code dist}, {@code @EventBusSubscriber} with
     * {@code value}; either way the absence of the member means both sides.
     */
    public static boolean runsOnThisSide(LoadedMod mod, String className, Set<String> descriptors) {
        ScannedAnnotation annotation = find(mod, className, descriptors);

        if (annotation == null) {
            return true;
        }

        Object declared = annotation.values().containsKey("dist") ? annotation.values().get("dist")
                : annotation.values().get("value") instanceof List<?> ? annotation.values().get("value") : null;

        return allows(declared);
    }

    private static boolean allows(Object declared) {
        if (!(declared instanceof List<?> sides) || sides.isEmpty()) {
            return true;
        }

        String wanted = OctoRuntime.get().side() == Side.CLIENT ? "CLIENT" : "DEDICATED_SERVER";

        for (Object side : sides) {
            String name = side instanceof ModAnnotation.EnumHolder holder ? holder.value() : String.valueOf(side);

            if (wanted.equals(name)) {
                return true;
            }
        }

        return false;
    }

    /** Whether a class carries one of these annotations at all. */
    public static boolean carries(LoadedMod mod, String className, Set<String> descriptors) {
        return find(mod, className, descriptors) != null;
    }

    private static ScannedAnnotation find(LoadedMod mod, String className, Set<String> descriptors) {
        String internal = className.replace('.', '/');

        for (ScannedAnnotation annotation : mod.candidate().bytecode().annotations()) {
            if (annotation.target() == ScannedAnnotation.Target.TYPE && descriptors.contains(annotation.descriptor())
                    && (annotation.className().equals(internal) || annotation.member().equals(className))) {
                return annotation;
            }
        }

        return null;
    }

    /** Subscribes every {@code @EventBusSubscriber} class of a mod to the buses it asks for. */
    public static void subscribe(LoadedMod mod, ClassLoader classLoader) {
        for (ScannedAnnotation annotation : mod.candidate().bytecode().annotations()) {
            if (annotation.target() != ScannedAnnotation.Target.TYPE || !SUBSCRIBERS.contains(annotation.descriptor())) {
                continue;
            }

            Object modId = annotation.values().get("modid");

            if (modId instanceof String id && !id.isEmpty() && !id.equals(mod.id())) {
                continue;
            }

            if (!allows(annotation.values().get("value"))) {
                continue;
            }

            String className = annotation.className().replace('/', '.');

            try {
                Class<?> type = Class.forName(className, true, classLoader);
                int count = subscribeStatics(mod, type);
                LOG.debug("{}: subscribed {} listener(s) from {}", mod.id(), count, className);
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                Throwable cause = Failures.unwrap(e);
                // One listener class that cannot load is one feature missing, not
                // a mod that is not running.
                LOG.warn("{}: could not subscribe {}: {}", mod.id(), className, Failures.describe(cause));
                OctoLog.detail(cause);
                mod.recordFix("event subscriber " + className + " could not be loaded: " + Failures.describe(cause));
            }
        }
    }

    private static int subscribeStatics(LoadedMod mod, Class<?> type) {
        int count = 0;

        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) {
                continue;
            }

            String annotation = subscribeAnnotationOf(method);

            if (annotation == null) {
                continue;
            }

            Class<?> eventType = method.getParameterTypes()[0];
            method.setAccessible(true);
            Consumer<Object> listener = event -> invoke(mod, method, event);

            try {
                switch (annotation) {
                    case EB7_SUBSCRIBE -> addEventBus7Listener(mod, eventType, listener);
                    case FORGE_SUBSCRIBE -> forgeBusFor(mod, eventType).addListener((Class) eventType,
                            (Consumer) listener);
                    default -> neoBusFor(mod, eventType).addListener((Class) eventType, (Consumer) listener);
                }

                count++;
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.debug("{}: could not subscribe {}.{}: {}", mod.id(), type.getName(), method.getName(),
                        e.toString());
            }
        }

        return count;
    }

    private static String subscribeAnnotationOf(Method method) {
        for (java.lang.annotation.Annotation annotation : method.getDeclaredAnnotations()) {
            String name = annotation.annotationType().getName();

            if (name.equals(EB7_SUBSCRIBE) || name.equals(FORGE_SUBSCRIBE) || name.equals(NEO_SUBSCRIBE)) {
                return name;
            }
        }

        return null;
    }

    private static void invoke(LoadedMod mod, Method method, Object event) {
        try {
            method.invoke(null, event);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            Throwable cause = Failures.unwrap(e);
            LOG.error("{}: listener {}.{} threw {}", mod.id(), method.getDeclaringClass().getName(),
                    method.getName(), Failures.describe(cause));
            OctoLog.detail(cause);
        }
    }

    /**
     * EventBus 7 has no bus to subscribe a class to; each event has its own. A
     * mod-bus event's lives in the mod's bus group, anything else's is the
     * event's static {@code BUS}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addEventBus7Listener(LoadedMod mod, Class<?> eventType, Consumer<Object> listener)
            throws ReflectiveOperationException {
        net.minecraftforge.eventbus.api.bus.EventBus bus;

        if (net.minecraftforge.fml.event.IModBusEvent.class.isAssignableFrom(eventType)) {
            bus = net.minecraftforge.fml.event.IModBusEvent.getBus(ForgeBuses.forgeBusGroupFor(mod.id()),
                    (Class) eventType);
        } else {
            Field field = eventType.getField("BUS");
            bus = (net.minecraftforge.eventbus.api.bus.EventBus) field.get(null);
        }

        if (bus != null) {
            bus.addListener((Consumer) listener);
        }
    }

    private static net.minecraftforge.eventbus.api.IEventBus forgeBusFor(LoadedMod mod, Class<?> eventType) {
        return net.minecraftforge.fml.event.IModBusEvent.class.isAssignableFrom(eventType)
                ? ForgeBuses.forgeBusFor(mod.id()) : ForgeBuses.commonForgeBus();
    }

    private static net.neoforged.bus.api.IEventBus neoBusFor(LoadedMod mod, Class<?> eventType) {
        return net.neoforged.fml.event.IModBusEvent.class.isAssignableFrom(eventType)
                ? ForgeBuses.neoBusFor(mod.id()) : ForgeBuses.commonNeoBus();
    }
}
