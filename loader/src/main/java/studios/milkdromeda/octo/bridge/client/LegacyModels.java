package studios.milkdromeda.octo.bridge.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.neoforged.neoforge.client.ChunkRenderTypeSet;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Block models drawn the way mods from before 26.x ask for them.
 *
 * <p>Until 26.x a mod drew a block's model through the game's
 * {@code BlockRenderDispatcher} — {@code renderSingleBlock}, or its model
 * renderer's {@code tesselateBlock} into a buffer of its own, which is how
 * Create builds every rotating part it draws. 26.x has none of that: models
 * are sets of parts read from {@code ModelManager}, and drawn by queueing
 * them. The old classes are added back from the release the mod came from,
 * and their methods answered here ({@code RestoredBridges}): the model is
 * this game's, and its quads are written into the buffer the mod gave,
 * through {@code VertexConsumer.putBakedQuad}.
 */
public final class LegacyModels {
    private static final OctoLog LOG = OctoLog.of(LegacyModels.class);
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final int NO_OVERLAY = 10 << 16;

    private static final Map<ClassLoader, Game> GAMES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Object> INSTANCES = new ConcurrentHashMap<>();
    private static final java.util.Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /** The models whose own quads are being read on this thread, so a model asking itself again falls through. */
    private static final ThreadLocal<java.util.Set<Object>> ASKING = ThreadLocal.withInitial(
            () -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

    private LegacyModels() {
    }

    // ------------------------------------------------------------------ shims

    /** {@code Minecraft.getBlockRenderer()}: the dispatcher added back from the mod's release, answered here. */
    public static Object blockRenderer(Object minecraft) {
        return instance(minecraft.getClass().getClassLoader(), "net.minecraft.client.renderer.block.BlockRenderDispatcher");
    }

    /** {@code model.getRenderTypes(state, random, data)}: the layer this model's geometry needs. */
    public static Object renderTypes(Object model, Object state, Object random, Object data) {
        if (model == null) {
            return ChunkRenderTypeSet.none();
        }

        Object type = game(model).renderTypeOf(model);
        return type == null ? ChunkRenderTypeSet.none() : ChunkRenderTypeSet.of(type);
    }

    /** {@code model.getModelData(level, pos, state, data)}: what it was handed; this game's models take none. */
    public static Object modelData(Object model, Object level, Object pos, Object state, Object data) {
        return data;
    }

    /** {@code model.getQuads(state, side, random)} and NeoForge's longer form: this game's quads on that side. */
    public static Object quads(Object model, Object state, Object side, Object random) {
        return model == null ? new ArrayList<>() : game(model).quads(model, side);
    }

    public static Object quads(Object model, Object state, Object side, Object random, Object data, Object type) {
        return quads(model, state, side, random);
    }

    /** {@code consumer.putBulkData(pose, quad, r, g, b, a, light, overlay)}: the quad, tinted and lit. */
    public static void putBulkData(Object consumer, Object pose, Object quad, float red, float green, float blue,
            float alpha, int light, int overlay) {
        Game game = game(consumer);
        game.put(consumer, pose, quad, red, green, blue, alpha, light, overlay, 1f);
    }

    // ----------------------------------------------------- restored classes

    public static Object call(Object self, String owner, String method, Object[] args) {
        try {
            ClassLoader loader = self != null ? self.getClass().getClassLoader() : loaderOf(args);
            return loader == null ? null : game(loader).call(owner, method, args);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);

            if (REPORTED.add(owner + "." + method)) {
                LOG.warn("{}.{}, answered for a mod from another version, failed: {}", owner.replace('/', '.'),
                        method, Failures.describe(Failures.unwrap(e)));
                OctoLog.detail(Failures.unwrap(e));
            }

            return null;
        }
    }

    public static void callV(Object self, String owner, String method, Object[] args) {
        call(self, owner, method, args);
    }

    public static boolean callZ(Object self, String owner, String method, Object[] args) {
        return call(self, owner, method, args) instanceof Boolean value && value;
    }

    public static int callI(Object self, String owner, String method, Object[] args) {
        return call(self, owner, method, args) instanceof Number value ? value.intValue() : 0;
    }

    public static long callJ(Object self, String owner, String method, Object[] args) {
        return call(self, owner, method, args) instanceof Number value ? value.longValue() : 0L;
    }

    public static float callF(Object self, String owner, String method, Object[] args) {
        return call(self, owner, method, args) instanceof Number value ? value.floatValue() : 0f;
    }

    public static double callD(Object self, String owner, String method, Object[] args) {
        return call(self, owner, method, args) instanceof Number value ? value.doubleValue() : 0d;
    }

    private static ClassLoader loaderOf(Object[] args) {
        for (Object arg : args) {
            if (arg != null && arg.getClass().getClassLoader() != null) {
                return arg.getClass().getClassLoader();
            }
        }

        return null;
    }

    static Object instance(ClassLoader loader, String name) {
        try {
            Class<?> type = Class.forName(name, true, loader);
            return INSTANCES.computeIfAbsent(type, LegacyModels::allocate);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** One without running its constructor, which wants what this game no longer has: its methods come here. */
    private static Object allocate(Class<?> type) {
        try {
            Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not make a " + type.getName(), e);
        }
    }

    private static Game game(Object anything) {
        return game(anything.getClass().getClassLoader());
    }

    private static Game game(ClassLoader loader) {
        return GAMES.computeIfAbsent(loader, Game::new);
    }

    /** The game's model and buffer classes, reached by reflection. */
    private static final class Game {
        final ClassLoader loader;
        final Class<?> modelType;
        final Class<?> blockState;
        final Class<?> poseStack;
        final Class<?> pose;
        final Class<?> vertexConsumer;
        final Method minecraft;
        final Method modelManager;
        final Method modelSet;
        final Method modelForState;
        final Method collectParts;
        final Method quadsOnSide;
        final Method hasMaterialFlag;
        final int translucentFlag;
        final Method quadDirection;
        final Method last;
        final Method putBakedQuad;
        final java.lang.reflect.Constructor<?> quadInstance;
        final Method setColor;
        final Method setLight;
        final Method setOverlay;
        final Object[] sides;
        final Method randomCreate;
        final Method solid;
        final Method cutout;
        final Method translucent;

        Game(ClassLoader loader) {
            this.loader = loader;

            try {
                modelType = type("net.minecraft.client.renderer.block.dispatch.BlockStateModel");
                Class<?> part = type("net.minecraft.client.renderer.block.dispatch.BlockStateModelPart");
                Class<?> quad = type("net.minecraft.client.resources.model.geometry.BakedQuad");
                Class<?> direction = type("net.minecraft.core.Direction");
                Class<?> random = type("net.minecraft.util.RandomSource");
                Class<?> instance = type("com.mojang.blaze3d.vertex.QuadInstance");
                blockState = type("net.minecraft.world.level.block.state.BlockState");
                poseStack = type("com.mojang.blaze3d.vertex.PoseStack");
                pose = type("com.mojang.blaze3d.vertex.PoseStack$Pose");
                vertexConsumer = type("com.mojang.blaze3d.vertex.VertexConsumer");
                Class<?> minecraftType = type("net.minecraft.client.Minecraft");
                minecraft = minecraftType.getMethod("getInstance");
                modelManager = minecraftType.getMethod("getModelManager");
                modelSet = type("net.minecraft.client.resources.model.ModelManager").getMethod("getBlockStateModelSet");
                modelForState = type("net.minecraft.client.renderer.block.BlockStateModelSet").getMethod("get", blockState);
                collectParts = modelType.getMethod("collectParts", random, List.class);
                quadsOnSide = part.getMethod("getQuads", direction);
                hasMaterialFlag = modelType.getMethod("hasMaterialFlag", int.class);
                translucentFlag = quad.getField("FLAG_TRANSLUCENT").getInt(null);
                quadDirection = quad.getMethod("direction");
                last = poseStack.getMethod("last");
                putBakedQuad = vertexConsumer.getMethod("putBakedQuad", pose, quad, instance);
                quadInstance = instance.getConstructor();
                setColor = instance.getMethod("setColor", int.class);
                setLight = instance.getMethod("setLightCoords", int.class);
                setOverlay = instance.getMethod("setOverlayCoords", int.class);
                Object[] directions = direction.getEnumConstants();
                sides = new Object[directions.length + 1];
                System.arraycopy(directions, 0, sides, 1, directions.length);
                randomCreate = random.getMethod("create", long.class);
                Class<?> renderTypes = type("net.minecraft.client.renderer.rendertype.RenderTypes");
                solid = renderTypes.getMethod("solidMovingBlock");
                cutout = renderTypes.getMethod("cutoutMovingBlock");
                translucent = renderTypes.getMethod("translucentMovingBlock");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("this game's block models cannot be bridged", e);
            }
        }

        private Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name, true, loader);
        }

        Object call(String owner, String method, Object[] args) throws ReflectiveOperationException {
            String simple = owner.substring(owner.lastIndexOf('/') + 1);

            return switch (simple + "." + method) {
                case "BlockRenderDispatcher.getBlockModel", "BlockModelShaper.getBlockModel" -> model(first(args, blockState));
                case "BlockRenderDispatcher.getModelRenderer" ->
                        instance(loader, "net.minecraft.client.renderer.block.ModelBlockRenderer");
                case "BlockRenderDispatcher.getBlockModelShaper" ->
                        instance(loader, "net.minecraft.client.renderer.block.BlockModelShaper");
                case "BlockRenderDispatcher.renderSingleBlock" -> {
                    Object state = first(args, blockState);
                    Object model = model(state);
                    Object buffers = args.length > 2 ? args[2] : null;
                    Object consumer = buffers == null ? null : buffer(buffers, renderTypeOf(model));
                    int[] ints = ints(args);
                    draw(model, first(args, poseStack), consumer, ints.length > 0 ? ints[0] : FULL_BRIGHT,
                            ints.length > 1 ? ints[1] : NO_OVERLAY, 1f, 1f, 1f, true);
                    yield null;
                }
                case "BlockRenderDispatcher.renderBatched", "BlockRenderDispatcher.renderBreakingTexture" -> {
                    Object state = first(args, blockState);
                    draw(model(state), first(args, poseStack), first(args, vertexConsumer), FULL_BRIGHT, NO_OVERLAY,
                            1f, 1f, 1f, true);
                    yield null;
                }
                case "ModelBlockRenderer.tesselateBlock", "ModelBlockRenderer.tesselateWithAO",
                        "ModelBlockRenderer.tesselateWithoutAO" -> {
                    Object model = first(args, modelType);
                    model = model != null ? model : model(first(args, blockState));
                    int[] ints = ints(args);
                    draw(model, first(args, poseStack), first(args, vertexConsumer), FULL_BRIGHT,
                            ints.length > 0 ? ints[ints.length - 1] : NO_OVERLAY, 1f, 1f, 1f, false);
                    yield null;
                }
                case "ModelBlockRenderer.renderModel" -> {
                    Object model = first(args, modelType);
                    model = model != null ? model : model(first(args, blockState));
                    float[] floats = floats(args);
                    int[] ints = ints(args);
                    Object where = first(args, pose);
                    draw(model, where != null ? where : first(args, poseStack), first(args, vertexConsumer),
                            ints.length > 0 ? ints[0] : FULL_BRIGHT, ints.length > 1 ? ints[1] : NO_OVERLAY,
                            floats.length > 0 ? floats[0] : 1f, floats.length > 1 ? floats[1] : 1f,
                            floats.length > 2 ? floats[2] : 1f, true);
                    yield null;
                }
                default -> null;
            };
        }

        /** This game's model for a block state, or {@code null} when there is no state. */
        Object model(Object state) throws ReflectiveOperationException {
            if (state == null) {
                return null;
            }

            Object set = modelSet.invoke(modelManager.invoke(minecraft.invoke(null)));
            return modelForState.invoke(set, state);
        }

        Object renderTypeOf(Object model) {
            try {
                // A model from the mod's own era has no material flags to ask;
                // its quads are block textures, drawn cut out as the game's are.
                if (model == null || !modelType.isInstance(model)) {
                    return cutout.invoke(null);
                }

                return (boolean) hasMaterialFlag.invoke(model, translucentFlag) ? translucent.invoke(null)
                        : cutout.invoke(null);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        List<Object> quads(Object model, Object side) {
            List<Object> own = ownQuads(model, side);

            if (own != null) {
                return own;
            }

            List<Object> out = new ArrayList<>();

            if (!modelType.isInstance(model)) {
                return out;
            }

            try {
                List<Object> parts = new ArrayList<>();
                collectParts.invoke(model, randomCreate.invoke(null, 42L), parts);

                for (Object part : parts) {
                    out.addAll((List<?>) quadsOnSide.invoke(part, side));
                }
            } catch (ReflectiveOperationException e) {
                LOG.debug("could not read a model's quads: {}", e.toString());
            }

            return out;
        }

        /**
         * The quads of a model from the mod's own era — one the mod built, or
         * the game's own class added back from the mod's release, such as
         * the {@code SimpleBakedModel} Create builds its water wheels on —
         * through its own {@code getQuads(state, side, random)}. Such a model
         * has no parts for this game to collect; its quads are what it is.
         *
         * @return null when the model is this game's, or is already being
         *         asked: its own method calling back through a shim lands here
         */
        List<Object> ownQuads(Object model, Object side) {
            Method own = model == null ? null : ownQuadsMethod(model.getClass());

            if (own == null || !ASKING.get().add(model)) {
                return null;
            }

            try {
                Object random = randomCreate.invoke(null, 42L);
                Object[] args = own.getParameterCount() == 3 ? new Object[] { null, side, random }
                        : new Object[] { null, side, random, emptyModelData(), null };
                Object quads = own.invoke(model, args);
                return quads instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.debug("could not read the quads of a {}: {}", model.getClass().getName(), e.toString());
                return new ArrayList<>();
            } finally {
                ASKING.get().remove(model);
            }
        }

        private final Map<Class<?>, java.util.Optional<Method>> ownQuads = new ConcurrentHashMap<>();

        Method ownQuadsMethod(Class<?> type) {
            return ownQuads.computeIfAbsent(type, ignored -> {
                Class<?> direction = sides[1].getClass().isEnum() ? sides[1].getClass()
                        : sides[1].getClass().getSuperclass();

                for (int arity : new int[] { 3, 5 }) {
                    for (Method method : type.getMethods()) {
                        if (method.getName().equals("getQuads") && method.getParameterCount() == arity
                                && method.getParameterTypes()[1] == direction
                                && !java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
                            method.setAccessible(true);
                            return java.util.Optional.of(method);
                        }
                    }
                }

                return java.util.Optional.empty();
            }).orElse(null);
        }

        private Object emptyModelData() {
            try {
                return Class.forName("net.neoforged.neoforge.client.model.data.ModelData", true, loader)
                        .getField("EMPTY").get(null);
            } catch (ReflectiveOperationException | LinkageError e) {
                return null;
            }
        }

        private Object buffer(Object buffers, Object type) {
            for (Method method : buffers.getClass().getMethods()) {
                if (method.getName().equals("getBuffer") && method.getParameterCount() == 1) {
                    try {
                        method.setAccessible(true);
                        return method.invoke(buffers, type);
                    } catch (ReflectiveOperationException e) {
                        return null;
                    }
                }
            }

            return null;
        }

        /** Every quad of the model, on every side and on none, written into the consumer at the pose. */
        void draw(Object model, Object where, Object consumer, int light, int overlay, float red, float green,
                float blue, boolean shade) throws ReflectiveOperationException {
            if (model == null || consumer == null || !vertexConsumer.isInstance(consumer)) {
                return;
            }

            Object at = poseStack.isInstance(where) ? last.invoke(where) : pose.isInstance(where) ? where
                    : last.invoke(poseStack.getConstructor().newInstance());

            if (ownQuadsMethod(model.getClass()) != null) {
                for (Object side : sides) {
                    List<Object> quads = ownQuads(model, side);

                    for (Object quad : quads == null ? List.of() : quads) {
                        float brightness = shade ? shadeOf(quadDirection.invoke(quad)) : 1f;
                        put(consumer, at, quad, red, green, blue, 1f, light, overlay, brightness);
                    }
                }

                return;
            }

            if (!modelType.isInstance(model)) {
                LOG.debug("a {} has neither this game's parts nor quads of its own to draw", model.getClass().getName());
                return;
            }

            List<Object> parts = new ArrayList<>();
            collectParts.invoke(model, randomCreate.invoke(null, 42L), parts);

            for (Object part : parts) {
                for (Object side : sides) {
                    for (Object quad : (List<?>) quadsOnSide.invoke(part, side)) {
                        float brightness = shade ? shadeOf(quadDirection.invoke(quad)) : 1f;
                        put(consumer, at, quad, red, green, blue, 1f, light, overlay, brightness);
                    }
                }
            }
        }

        void put(Object consumer, Object at, Object quad, float red, float green, float blue, float alpha, int light,
                int overlay, float brightness) {
            try {
                Object instance = quadInstance.newInstance();
                int color = channel(alpha, 1f) << 24 | channel(red, brightness) << 16 | channel(green, brightness) << 8
                        | channel(blue, brightness);
                setColor.invoke(instance, color);
                setLight.invoke(instance, light);
                setOverlay.invoke(instance, overlay);
                putBakedQuad.invoke(consumer, poseStack.isInstance(at) ? last.invoke(at) : at, quad, instance);
            } catch (ReflectiveOperationException e) {
                LOG.debug("could not write a quad: {}", e.toString());
            }
        }

        private static int channel(float value, float brightness) {
            return Math.max(0, Math.min(255, Math.round(value * brightness * 255f)));
        }

        /** The game's own flat shading by face: darkest below, brightest above. */
        private static float shadeOf(Object direction) {
            return switch (String.valueOf(direction)) {
                case "down" -> 0.5f;
                case "north", "south" -> 0.8f;
                case "east", "west" -> 0.6f;
                default -> 1f;
            };
        }

        private static Object first(Object[] args, Class<?> type) {
            for (Object arg : args) {
                if (type.isInstance(arg)) {
                    return arg;
                }
            }

            return null;
        }

        private static int[] ints(Object[] args) {
            return java.util.Arrays.stream(args).filter(Integer.class::isInstance).mapToInt(arg -> (int) arg).toArray();
        }

        private static float[] floats(Object[] args) {
            List<Float> found = new ArrayList<>();

            for (Object arg : args) {
                if (arg instanceof Float value) {
                    found.add(value);
                }
            }

            float[] out = new float[found.size()];

            for (int i = 0; i < out.length; i++) {
                out[i] = found.get(i);
            }

            return out;
        }
    }
}
