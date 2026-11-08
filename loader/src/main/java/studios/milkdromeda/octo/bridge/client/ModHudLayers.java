package studios.milkdromeda.octo.bridge.client;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * What NeoForge mods draw over the game: their HUD layers.
 *
 * <p>NeoForge's patched HUD asks mods for layers with
 * {@code RegisterGuiLayersEvent} and draws them among its own. Octo runs the
 * game's own HUD, so nothing asked and nothing drew: Create's goggles told
 * nothing of what they looked at, and no mod's overlay appeared. Here the
 * event is fired with the game's layers to order against, and what the mods
 * add is drawn after the game's HUD each frame, in the order they gave.
 *
 * <p>Layers as mods wrote them before 26.x drew on a {@code GuiGraphics};
 * {@link #legacy} turns one into a layer of this version, drawing through
 * the {@code GuiGraphics} Octo provides over the frame's extractor.
 */
public final class ModHudLayers {
    private static final OctoLog LOG = OctoLog.of(ModHudLayers.class);

    private static volatile List<Object> layers = List.of();
    private static volatile Method render;
    /** The layers that have failed, each said once: by identity, as every older layer is the same proxy class. */
    private static final Set<Object> FAILED = java.util.Collections.newSetFromMap(
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>()));

    private ModHudLayers() {
    }

    /** Fires {@code RegisterGuiLayersEvent} and keeps the layers the mods added. */
    public static int register(ClassLoader loader, java.util.function.Consumer<Object> post)
            throws ReflectiveOperationException {
        Class<?> layerType = Class.forName("net.neoforged.neoforge.client.gui.GuiLayer", true, loader);
        Class<?> namedType = Class.forName("net.neoforged.neoforge.client.gui.GuiLayerManager$NamedLayer", true,
                loader);
        Class<?> vanilla = Class.forName("net.neoforged.neoforge.client.gui.VanillaGuiLayers", true, loader);
        Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", true, loader);
        Object placeholder = Proxy.newProxyInstance(loader, new Class<?>[] { layerType }, inert("the game's layer"));
        List<Object> named = new ArrayList<>();
        Set<Object> vanillaIds = new java.util.HashSet<>();

        // The game's own layers, in its order, for mods to place theirs against.
        for (java.lang.reflect.Field field : vanilla.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == identifier) {
                Object id = field.get(null);
                vanillaIds.add(id);
                named.add(namedType.getConstructor(identifier, layerType).newInstance(id, placeholder));
            }
        }

        Object event = Class.forName("net.neoforged.neoforge.client.event.RegisterGuiLayersEvent", true, loader)
                .getConstructor(List.class).newInstance(named);
        post.accept(event);

        Method name = namedType.getMethod("name");
        Method layer = namedType.getMethod("layer");
        List<Object> out = new ArrayList<>();

        for (Object entry : named) {
            Object drawn = layer.invoke(entry);
            Object id = name.invoke(entry);

            // The game draws its own; a mod's wrapper round one of those would draw nothing here.
            if (drawn == null) {
                LOG.warn("HUD layer {} was registered as nothing and is skipped", id);
            } else if (drawn != placeholder && !vanillaIds.contains(id)) {
                out.add(drawn);
            }
        }

        render = layerType.getMethod("render", Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor", true,
                loader), Class.forName("net.minecraft.client.DeltaTracker", true, loader));
        layers = List.copyOf(out);
        return out.size();
    }

    /** Called as the game's HUD finishes collecting what to draw: the mods' layers, over it. */
    public static void extract(Object hud, Object extractor, Object deltaTracker) {
        List<Object> drawing = layers;
        Method draw = render;

        if (drawing.isEmpty() || draw == null || extractor == null || hidden(hud)) {
            return;
        }

        for (Object layer : drawing) {
            try {
                draw.invoke(layer, extractor, deltaTracker);
                flushRaised(hud);
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);

                if (FAILED.add(layer)) {
                    LOG.warn("a mod's HUD layer ({}) failed and was skipped: {}", layer,
                            Failures.describe(Failures.unwrap(e)));
                    OctoLog.detail(Failures.unwrap(e));
                }
            }
        }
    }

    private static volatile Method flushRaised;

    /** What an older layer raised above the rest (Create's goggles icon over its tooltip), drawn after it. */
    private static void flushRaised(Object hud) throws ReflectiveOperationException {
        Method flush = flushRaised;

        if (flush == null) {
            try {
                flush = Class.forName("net.minecraft.client.gui.GuiGraphics", true, hud.getClass().getClassLoader())
                        .getMethod("octo$flushRaised");
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                return;
            }

            flushRaised = flush;
        }

        flush.invoke(null);
    }

    /** {@code hud.isHidden()}: the player has hidden the HUD. */
    private static boolean hidden(Object hud) {
        try {
            return hud != null && Boolean.TRUE.equals(hud.getClass().getMethod("isHidden").invoke(hud));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * A layer as mods wrote one before 26.x — {@code LayeredDraw.Layer}, drawing
     * on a {@code GuiGraphics} — as a layer of this version.
     */
    public static Object legacy(Object old, Class<?> layerType) {
        if (old == null || layerType.isInstance(old)) {
            return old;
        }

        ClassLoader loader = layerType.getClassLoader();
        Method[] oldRender = { null };
        Method[] view = { null };

        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getName().equals("render") && arguments != null && arguments.length == 2) {
                if (oldRender[0] == null) {
                    Class<?> graphics = Class.forName("net.minecraft.client.gui.GuiGraphics", true,
                            old.getClass().getClassLoader());
                    view[0] = graphics.getMethod("octo$of", Class.forName(
                            "net.minecraft.client.gui.GuiGraphicsExtractor", true, loader));
                    oldRender[0] = findRender(old.getClass(), graphics);
                }

                oldRender[0].invoke(old, view[0].invoke(null, arguments[0]), arguments[1]);
                return null;
            }

            return inert(old.toString()).invoke(proxy, method, arguments);
        };

        return Proxy.newProxyInstance(loader, new Class<?>[] { layerType }, handler);
    }

    private static Method findRender(Class<?> type, Class<?> graphics) throws NoSuchMethodException {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            for (Method method : at.getMethods()) {
                if (method.getName().equals("render") && method.getParameterCount() == 2
                        && method.getParameterTypes()[0] == graphics) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }

        throw new NoSuchMethodException(type.getName() + ".render(GuiGraphics, DeltaTracker)");
    }

    private static InvocationHandler inert(String name) {
        return (proxy, method, arguments) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, arguments);
            }

            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> name;
                default -> null;
            };
        };
    }
}
