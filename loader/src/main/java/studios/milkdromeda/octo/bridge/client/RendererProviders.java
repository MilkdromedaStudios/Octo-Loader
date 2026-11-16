package studios.milkdromeda.octo.bridge.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * An entity renderer that cannot be made costs that entity its renderer.
 *
 * <p>The game makes every renderer in one pass and gives up on all of them at
 * the first that fails, and the reload it is part of with them: one mob of a
 * mod's whose model this version has changed under it left the game with no
 * renderers and no particles, and it crashed on the first particle. A
 * renderer that fails to be made is named in the log, and the entity gets the
 * game's renderer that draws nothing in its place.
 */
public final class RendererProviders {
    private static final OctoLog LOG = OctoLog.of(RendererProviders.class);
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    /** The entity types whose own renderer cannot draw here. */
    private static final Set<Object> PENDING = ConcurrentHashMap.newKeySet();
    /** What draws each of those instead, once one has been seen. */
    private static final java.util.Map<Object, Object> STAND_INS = new ConcurrentHashMap<>();
    private static final Object NONE = new Object();

    /**
     * Called as {@code EntityRenderDispatcher.getRenderer(entity)} returns.
     *
     * <p>A mob whose own renderer cannot draw here is drawn by the game's
     * renderer for the nearest mob it extends: a mod's zombie by the zombie's.
     * It looks like that mob, so it carries its name ({@link #nameTag}).
     */
    public static Object forEntity(Object found, Object entity, Object dispatcher) {
        try {
            if (PENDING.isEmpty() || entity == null) {
                return found;
            }

            Object type = entity.getClass().getMethod("getType").invoke(entity);

            if (!PENDING.contains(type)) {
                return found;
            }

            Object standIn = STAND_INS.get(type);

            if (standIn == null) {
                standIn = standInFor(entity, dispatcher);
                STAND_INS.put(type, standIn);

                if (standIn == NONE) {
                    LOG.warn("{} is not drawn; a name tag shows where it is", type);
                } else {
                    LOG.warn("{} is drawn by the game's {}, under its own name", type,
                            standIn.getClass().getSimpleName());
                }
            }

            return standIn == NONE ? found : standIn;
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            return found;
        }
    }

    /** Called as {@code EntityRenderDispatcher.getRenderer(state)} returns: the same answer, for the state. */
    public static Object forState(Object found, Object state) {
        try {
            if (STAND_INS.isEmpty() || state == null) {
                return found;
            }

            Object standIn = STAND_INS.get(state.getClass().getField("entityType").get(state));
            return standIn == null || standIn == NONE ? found : standIn;
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            return found;
        }
    }

    private static Object standInFor(Object entity, Object dispatcher) throws ReflectiveOperationException {
        ClassLoader loader = entity.getClass().getClassLoader();
        Class<?> base = Class.forName("net.minecraft.client.renderer.entity.EntityRenderer", false, loader);
        java.lang.reflect.Field table = dispatcher.getClass().getDeclaredField("renderers");
        table.setAccessible(true);
        java.util.Map<?, ?> renderers = (java.util.Map<?, ?>) table.get(dispatcher);

        for (Class<?> type = entity.getClass().getSuperclass(); type != null
                && type.getName().startsWith("net.minecraft.world.entity.") && !type.getName().endsWith(".Entity");
                type = type.getSuperclass()) {
            Object fits = null;

            for (Object candidate : renderers.values()) {
                if (candidate != null && candidate.getClass().getName().startsWith("net.minecraft.")
                        && entityClassOf(candidate.getClass(), base) == type) {
                    // The mob's own renderer before one of its variants':
                    // ZombieRenderer for a Zombie, not the husk's.
                    if (candidate.getClass().getSimpleName().equals(type.getSimpleName() + "Renderer")) {
                        return candidate;
                    }

                    if (fits == null) {
                        fits = candidate;
                    }
                }
            }

            if (fits != null) {
                return fits;
            }
        }

        return NONE;
    }

    /**
     * Called as {@code EntityRenderer.extractRenderState} returns: a mob drawn
     * by a stand-in, or not drawn at all, always shows its name, so that a
     * sickened zombie that looks like any zombie says which it is.
     */
    public static void nameTag(Object entity, Object state, float partialTick) {
        try {
            if (PENDING.isEmpty() || entity == null || state == null) {
                return;
            }

            Object type = entity.getClass().getMethod("getType").invoke(entity);

            if (!PENDING.contains(type)) {
                return;
            }

            java.lang.reflect.Field tag = state.getClass().getField("nameTag");

            if (tag.get(state) == null) {
                tag.set(state, type.getClass().getMethod("getDescription").invoke(type));
            }

            java.lang.reflect.Field attachment = state.getClass().getField("nameTagAttachment");

            if (attachment.get(state) == null) {
                float height = ((Number) entity.getClass().getMethod("getBbHeight").invoke(entity)).floatValue();
                attachment.set(state, attachment.getType().getConstructor(double.class, double.class, double.class)
                        .newInstance(0.0, (double) height + 0.5, 0.0));
            }
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
        }
    }

    private RendererProviders() {
    }

    /**
     * Called as {@code EntityRenderers.createEntityRenderers} returns.
     *
     * <p>A mob's renderer written before 1.21.2 knows nothing of the render
     * state the game now asks every renderer for, and the first such mob to
     * come into view ended the game on a failed cast. One that answers with
     * the wrong kind of state is replaced: by the game's own renderer for
     * the mob it was built on, where it extends one — a mod's cow is drawn
     * as a cow — and otherwise by the renderer that draws nothing.
     */
    public static java.util.Map<?, ?> withFallbacks(java.util.Map<?, ?> renderers, Object context) {
        try {
            ClassLoader loader = context.getClass().getClassLoader();
            Class<?> living = Class.forName("net.minecraft.client.renderer.entity.LivingEntityRenderer", false, loader);
            Class<?> livingState = Class.forName("net.minecraft.client.renderer.entity.state.LivingEntityRenderState",
                    false, loader);
            java.lang.reflect.Method newState = Class.forName("net.minecraft.client.renderer.entity.EntityRenderer",
                    false, loader).getMethod("createRenderState");
            java.util.Map<Object, Object> out = new java.util.LinkedHashMap<>(renderers);

            for (java.util.Map.Entry<?, ?> entry : renderers.entrySet()) {
                Object renderer = entry.getValue();

                if (!living.isInstance(renderer) || renderer.getClass().getName().startsWith("net.minecraft.")) {
                    continue;
                }

                Object state;

                try {
                    state = newState.invoke(renderer);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    state = null;
                }

                if (livingState.isInstance(state)) {
                    continue;
                }

                // Nothing of its own draws it, then; which of the game's
                // renderers can stand in is settled when the first one is seen.
                Object fallback = Class.forName("net.minecraft.client.renderer.entity.NoopRenderer", true, loader)
                        .getConstructor(context.getClass()).newInstance(context);
                STAND_INS.remove(entry.getKey());
                PENDING.add(entry.getKey());
                LOG.warn("{}'s renderer was written for an older Minecraft and cannot draw here", entry.getKey());
                out.put(entry.getKey(), fallback);
            }

            return out;
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("older mods' renderers could not be checked: {}", Failures.describe(Failures.unwrap(e)));
            return renderers;
        }
    }

    /**
     * The entity class a renderer class is declared to draw: the first type
     * argument it gives {@code EntityRenderer}, followed through its
     * superclasses. {@code null} when its declaration cannot be read.
     */
    static Class<?> entityClassOf(Class<?> renderer, Class<?> base) {
        try {
            java.util.Map<java.lang.reflect.TypeVariable<?>, java.lang.reflect.Type> bound = new java.util.HashMap<>();

            for (Class<?> type = renderer; type != null && type != Object.class; type = type.getSuperclass()) {
                java.lang.reflect.Type above = type.getGenericSuperclass();
                Class<?> raw = type.getSuperclass();

                if (above instanceof java.lang.reflect.ParameterizedType parameterized && raw != null) {
                    java.lang.reflect.Type[] arguments = parameterized.getActualTypeArguments();
                    java.lang.reflect.TypeVariable<?>[] variables = raw.getTypeParameters();

                    for (int i = 0; i < arguments.length && i < variables.length; i++) {
                        bound.put(variables[i], arguments[i]);
                    }
                }

                if (raw == base) {
                    java.lang.reflect.Type drawn = base.getTypeParameters()[0];

                    for (int steps = 0; steps < 16; steps++) {
                        if (drawn instanceof Class<?> found) {
                            return found;
                        }

                        if (drawn instanceof java.lang.reflect.ParameterizedType parameterized) {
                            drawn = parameterized.getRawType();
                        } else if (drawn instanceof java.lang.reflect.TypeVariable<?> variable) {
                            drawn = bound.containsKey(variable) ? bound.get(variable) : variable.getBounds()[0];
                        } else {
                            return null;
                        }
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // A declaration naming a class that is not here: nothing to go on.
        }

        return null;
    }

    /** Called first thing in {@code EntityRenderers.register}: the provider, made safe to call. */
    public static Object guard(Object entityType, Object provider) {
        if (provider == null || Proxy.isProxyClass(provider.getClass())) {
            return provider;
        }

        try {
            ClassLoader loader = provider.getClass().getClassLoader();
            Class<?> providerType = Class.forName("net.minecraft.client.renderer.entity.EntityRendererProvider", false,
                    loader);

            return Proxy.newProxyInstance(loader, new Class<?>[] { providerType }, (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> "guarded " + provider;
                    };
                }

                try {
                    method.setAccessible(true);
                    return method.invoke(provider, arguments);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    Failures.rethrowIfFatal(cause);

                    if (!method.getName().equals("create") || arguments == null || arguments.length != 1) {
                        throw cause;
                    }

                    PENDING.add(entityType);

                    if (REPORTED.add(String.valueOf(entityType))) {
                        LOG.warn("the renderer for {} could not be made, so it is not drawn: {}", entityType,
                                Failures.describe(cause));
                        OctoLog.detail(cause);
                    }

                    Class<?> context = method.getParameterTypes()[0];
                    return Class.forName("net.minecraft.client.renderer.entity.NoopRenderer", true, loader)
                            .getConstructor(context).newInstance(arguments[0]);
                }
            });
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            return provider;
        }
    }
}
