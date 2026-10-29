package studios.milkdromeda.octo.bridge.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Draws block entity renderers from before 26.x with 26.x's renderer.
 *
 * <p>Until 26.x a block entity renderer drew straight into a set of buffers,
 * {@code render(blockEntity, partialTick, poseStack, buffers, light, overlay)}.
 * Now the game first copies what it needs into a render state and later
 * hands that state to {@code submit}, which queues geometry rather than
 * drawing it. A renderer written the old way answered neither, and every
 * moving part it drew — Create's cogwheels, shafts, press heads, belts — was
 * simply not there.
 *
 * <p>So the old renderer keeps working the old way. Extracting its state keeps
 * the block entity and the partial tick; submitting runs its old drawing code
 * into buffers that record every vertex, and each buffer's vertices are then
 * queued as custom geometry of the render type it was asked for. What it
 * draws looks as it did, at the cost of a copy of each vertex.
 */
public final class LegacyRenderers {
    private static final OctoLog LOG = OctoLog.of(LegacyRenderers.class);

    /** What each extracted state was extracted from, until the state is dropped. */
    private static final Map<Object, Captured> CAPTURED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ClassLoader, Game> GAMES = new ConcurrentHashMap<>();
    private static final Set<Class<?>> REPORTED = ConcurrentHashMap.newKeySet();

    private record Captured(Object blockEntity, float partialTick, Object camera) {
    }

    private LegacyRenderers() {
    }

    /** {@code createRenderState()}: a plain state; what the old renderer needs is kept beside it. */
    public static Object newState(Object renderer) {
        return game(renderer).newState();
    }

    /** {@code extractRenderState(...)}, after the game's own part: remember what to draw from. */
    public static void capture(Object state, Object blockEntity, float partialTick, Object camera) {
        CAPTURED.put(state, new Captured(blockEntity, partialTick, camera));
    }

    /** {@code submit(state, poseStack, collector, camera)}: draw the old way, and queue what was drawn. */
    public static void submit(Object renderer, Object state, Object poseStack, Object collector) {
        Captured captured = CAPTURED.get(state);

        if (captured == null || !(renderer instanceof LegacyRenderer legacy)) {
            return;
        }

        Game game = game(renderer);
        Recording recording = new Recording(game);

        try {
            legacy.octo$render(captured.blockEntity(), captured.partialTick(), poseStack, recording.buffers(),
                    game.light(state), game.noOverlay, captured.camera());
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);

            if (REPORTED.add(renderer.getClass())) {
                LOG.warn("{} failed drawing the old way; what it drew before failing is shown: {}",
                        renderer.getClass().getName(), Failures.describe(Failures.unwrap(e)));
                OctoLog.detail(Failures.unwrap(e));
            }
        }

        recording.submit(collector);
    }

    private static Game game(Object renderer) {
        ClassLoader loader = renderer.getClass().getClassLoader();
        return GAMES.computeIfAbsent(loader, Game::new);
    }

    /** The game's rendering classes, reached by reflection: Octo is not built against them. */
    private static final class Game {
        final ClassLoader loader;
        final Constructor<?> stateConstructor;
        final Field lightCoords;
        final int noOverlay;
        final Class<?> vertexConsumer;
        final Class<?> buffers;
        final Class<?> geometryRenderer;
        final Constructor<?> poseStack;
        final Method submitCustomGeometry;
        final Object fallbackType;
        final Method addVertex;
        final Method setColor;
        final Method setUv;
        final Method setUv1;
        final Method setUv2;
        final Method setNormal;

        Game(ClassLoader loader) {
            this.loader = loader;

            try {
                Class<?> state = type("net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState");
                stateConstructor = state.getConstructor();
                lightCoords = state.getField("lightCoords");
                noOverlay = type("net.minecraft.client.renderer.texture.OverlayTexture").getField("NO_OVERLAY")
                        .getInt(null);
                vertexConsumer = type("com.mojang.blaze3d.vertex.VertexConsumer");
                buffers = type("net.minecraft.client.renderer.MultiBufferSource");
                geometryRenderer = type("net.minecraft.client.renderer.SubmitNodeCollector$CustomGeometryRenderer");
                Class<?> pose = type("com.mojang.blaze3d.vertex.PoseStack");
                poseStack = pose.getConstructor();
                Class<?> renderType = type("net.minecraft.client.renderer.rendertype.RenderType");
                submitCustomGeometry = type("net.minecraft.client.renderer.OrderedSubmitNodeCollector")
                        .getMethod("submitCustomGeometry", pose, renderType, geometryRenderer);
                fallbackType = type("net.minecraft.client.renderer.rendertype.RenderTypes")
                        .getMethod("cutoutMovingBlock").invoke(null);
                addVertex = vertexConsumer.getMethod("addVertex", float.class, float.class, float.class);
                setColor = vertexConsumer.getMethod("setColor", int.class);
                setUv = vertexConsumer.getMethod("setUv", float.class, float.class);
                setUv1 = vertexConsumer.getMethod("setUv1", int.class, int.class);
                setUv2 = vertexConsumer.getMethod("setUv2", int.class, int.class);
                setNormal = vertexConsumer.getMethod("setNormal", float.class, float.class, float.class);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("this game's block entity renderer cannot be bridged", e);
            }
        }

        private Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name, true, loader);
        }

        Object newState() {
            try {
                return stateConstructor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        int light(Object state) {
            try {
                return lightCoords.getInt(state);
            } catch (IllegalAccessException e) {
                return 0xF000F0;
            }
        }
    }

    /** One render type's vertices, as an old renderer wrote them. */
    private static final class Vertices {
        private static final int STRIDE = 11;
        /** {@code OverlayTexture.NO_OVERLAY}, and the light of a vertex nothing has lit. */
        private static final int NO_OVERLAY = 10 << 16;
        private static final int FULL_BRIGHT = 0xF000F0;
        float[] data = new float[STRIDE * 64];
        int count;

        int start(float x, float y, float z) {
            if ((count + 1) * STRIDE > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }

            int at = count++ * STRIDE;
            data[at] = x;
            data[at + 1] = y;
            data[at + 2] = z;
            data[at + 3] = Float.intBitsToFloat(-1);
            data[at + 4] = 0;
            data[at + 5] = 0;
            data[at + 6] = Float.intBitsToFloat(NO_OVERLAY);
            data[at + 7] = Float.intBitsToFloat(FULL_BRIGHT);
            data[at + 8] = 0;
            data[at + 9] = 1;
            data[at + 10] = 0;
            return at;
        }

        int current() {
            return count == 0 ? -1 : (count - 1) * STRIDE;
        }
    }

    /** The buffers an old renderer draws into, and what it drew. */
    private static final class Recording {
        private final Game game;
        private final Map<Object, Vertices> byType = new LinkedHashMap<>();

        Recording(Game game) {
            this.game = game;
        }

        Object buffers() {
            InvocationHandler handler = (proxy, method, arguments) -> {
                if (method.getName().equals("getBuffer") && arguments != null && arguments.length == 1) {
                    Object type = arguments[0] == null ? game.fallbackType : arguments[0];
                    return consumer(byType.computeIfAbsent(type, ignored -> new Vertices()));
                }

                return objectMethod(proxy, method, arguments);
            };
            return Proxy.newProxyInstance(game.loader, new Class<?>[] { game.buffers }, handler);
        }

        private Object consumer(Vertices vertices) {
            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
                    int at = vertices.current();

                    switch (method.getName() + "/" + method.getParameterCount()) {
                        case "addVertex/3" -> {
                            if (method.getParameterTypes()[0] == float.class) {
                                vertices.start((float) arguments[0], (float) arguments[1], (float) arguments[2]);
                                return proxy;
                            }
                        }
                        case "setColor/4" -> {
                            if (at >= 0 && method.getParameterTypes()[0] == int.class) {
                                int argb = ((int) arguments[3] & 0xFF) << 24 | ((int) arguments[0] & 0xFF) << 16
                                        | ((int) arguments[1] & 0xFF) << 8 | (int) arguments[2] & 0xFF;
                                vertices.data[at + 3] = Float.intBitsToFloat(argb);
                                return proxy;
                            }
                        }
                        case "setColor/1" -> {
                            if (at >= 0) {
                                vertices.data[at + 3] = Float.intBitsToFloat((int) arguments[0]);
                            }

                            return proxy;
                        }
                        case "setUv/2" -> {
                            if (at >= 0) {
                                vertices.data[at + 4] = (float) arguments[0];
                                vertices.data[at + 5] = (float) arguments[1];
                            }

                            return proxy;
                        }
                        case "setUv1/2" -> {
                            if (at >= 0) {
                                vertices.data[at + 6] = Float.intBitsToFloat(pack(arguments));
                            }

                            return proxy;
                        }
                        case "setUv2/2" -> {
                            if (at >= 0) {
                                vertices.data[at + 7] = Float.intBitsToFloat(pack(arguments));
                            }

                            return proxy;
                        }
                        case "setNormal/3" -> {
                            if (at >= 0 && method.getParameterTypes()[0] == float.class) {
                                vertices.data[at + 8] = (float) arguments[0];
                                vertices.data[at + 9] = (float) arguments[1];
                                vertices.data[at + 10] = (float) arguments[2];
                                return proxy;
                            }
                        }
                        case "setUv3/2", "setLineWidth/1" -> {
                            return proxy;
                        }
                        default -> {
                        }
                    }

                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, arguments);
                    }

                    Object answer = objectMethod(proxy, method, arguments);
                    return answer == null && method.getReturnType().isInstance(proxy) ? proxy : answer;
                }
            };
            return Proxy.newProxyInstance(game.loader, new Class<?>[] { game.vertexConsumer }, handler);
        }

        void submit(Object collector) {
            for (Map.Entry<Object, Vertices> entry : byType.entrySet()) {
                Vertices vertices = entry.getValue();

                if (vertices.count == 0) {
                    continue;
                }

                try {
                    Object geometry = Proxy.newProxyInstance(game.loader, new Class<?>[] { game.geometryRenderer },
                            (proxy, method, arguments) -> {
                                if (method.getName().equals("render") && arguments != null && arguments.length == 2) {
                                    replay(vertices, arguments[1]);
                                    return null;
                                }

                                return objectMethod(proxy, method, arguments);
                            });
                    game.submitCustomGeometry.invoke(collector, game.poseStack.newInstance(), entry.getKey(), geometry);
                } catch (Throwable e) {
                    Failures.rethrowIfFatal(e);
                    LOG.debug("could not queue an old renderer's geometry: {}", e.toString());
                }
            }
        }

        /** The recorded vertices, written into the game's buffer; they are already where they belong. */
        private void replay(Vertices vertices, Object consumer) throws ReflectiveOperationException {
            float[] data = vertices.data;

            for (int i = 0; i < vertices.count; i++) {
                int at = i * Vertices.STRIDE;
                game.addVertex.invoke(consumer, data[at], data[at + 1], data[at + 2]);
                game.setColor.invoke(consumer, Float.floatToRawIntBits(data[at + 3]));
                game.setUv.invoke(consumer, data[at + 4], data[at + 5]);
                int overlay = Float.floatToRawIntBits(data[at + 6]);
                game.setUv1.invoke(consumer, overlay & 0xFFFF, overlay >>> 16);
                int light = Float.floatToRawIntBits(data[at + 7]);
                game.setUv2.invoke(consumer, light & 0xFFFF, light >>> 16);
                game.setNormal.invoke(consumer, data[at + 8], data[at + 9], data[at + 10]);
            }
        }

        private static int pack(Object[] arguments) {
            return ((int) arguments[1] & 0xFFFF) << 16 | (int) arguments[0] & 0xFFFF;
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> arguments != null && proxy == arguments[0];
            case "toString" -> "Octo's recording of an old renderer";
            default -> defaultFor(method.getReturnType());
        };
    }

    private static Object defaultFor(Class<?> type) {
        return switch (type.getName()) {
            case "boolean" -> Boolean.FALSE;
            case "long" -> 0L;
            case "double" -> 0d;
            case "float" -> 0f;
            case "char" -> (char) 0;
            case "byte" -> (byte) 0;
            case "short" -> (short) 0;
            case "int" -> 0;
            default -> null;
        };
    }
}
