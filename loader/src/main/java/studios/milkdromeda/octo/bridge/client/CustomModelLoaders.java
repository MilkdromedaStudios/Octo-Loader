package studios.milkdromeda.octo.bridge.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * The model loaders NeoForge's and Forge's patched client add, for models
 * read by the plain game: {@code neoforge:obj} (and {@code forge:obj}), a
 * Wavefront mesh, and {@code neoforge:composite} (and {@code forge:composite}),
 * a model made of named parts.
 *
 * <p>The game reads a model file's {@code parent}, {@code textures} and
 * {@code display} and ignores the {@code loader} it doesn't know, so such a
 * model came out with no shape at all: the block was there and invisible,
 * the item blank. Create's blaze burner, flywheel, chain conveyor, valve
 * handles, display and stock links are drawn this way. Here the file is
 * read as the game reads it, and its shape is given by a geometry that bakes
 * the mesh, or each part, into the game's own quads.
 */
public final class CustomModelLoaders {
    private static final OctoLog LOG = OctoLog.of(CustomModelLoaders.class);

    /** Meshes read this resource reload, by file: one manager's worth at a time. */
    private static final Map<String, List<ObjMesh.Face>> MESHES = new ConcurrentHashMap<>();
    private static volatile Object meshesFrom;

    private CustomModelLoaders() {
    }

    /**
     * {@code CuboidModel.fromStream(reader)}, which this replaces; the game's
     * own reading is {@code octo$fromStream}.
     */
    public static Object fromStream(Reader reader, Class<?> modelType) throws IOException {
        String text = readAll(reader);
        Method original;

        try {
            original = modelType.getMethod("octo$fromStream", Reader.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("the game's model reading was not kept", e);
        }

        // Nearly every model has no loader: those go straight to the game, read once.
        if (!text.contains("\"loader\"")) {
            return read(original, text);
        }

        JsonObject json;

        try {
            JsonElement parsed = JsonParser.parseString(text);

            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("loader")) {
                return read(original, text);
            }

            json = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            // Not JSON this reading understands: the game says why.
            return read(original, text);
        }

        String loader = json.get("loader").getAsString();

        try {
            return switch (loader) {
                case "neoforge:obj", "forge:obj" -> obj(json, modelType, original);
                case "neoforge:composite", "forge:composite" -> composite(json, modelType, original);
                default -> read(original, text);
            };
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("a {} model could not be read; it is drawn without its shape: {}", loader,
                    Failures.describe(Failures.unwrap(e)));
            return read(original, text);
        }
    }

    private static Object read(Method original, String text) {
        try {
            return original.invoke(null, new StringReader(text));
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw sneaky(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable e) throws T {
        throw (T) e;
    }

    private static String readAll(Reader reader) throws IOException {
        StringBuilder out = new StringBuilder();
        char[] buffer = new char[8192];
        int read;

        while ((read = reader.read(buffer)) > 0) {
            out.append(buffer, 0, read);
        }

        return out.toString();
    }

    /** The model as the game reads it, with {@code geometry} in place of its own (none). */
    private static Object withGeometry(Object base, Class<?> modelType, Object geometry)
            throws ReflectiveOperationException {
        Class<?> geometryType = modelType.getMethod("geometry").getReturnType();
        Class<?> guiLight = modelType.getMethod("guiLight").getReturnType();
        Class<?> transforms = modelType.getMethod("transforms").getReturnType();
        Class<?> slots = modelType.getMethod("textureSlots").getReturnType();
        Class<?> parent = modelType.getMethod("parent").getReturnType();
        return modelType.getConstructor(geometryType, guiLight, Boolean.class, transforms, slots, parent)
                .newInstance(geometry, modelType.getMethod("guiLight").invoke(base),
                        modelType.getMethod("ambientOcclusion").invoke(base),
                        modelType.getMethod("transforms").invoke(base),
                        modelType.getMethod("textureSlots").invoke(base), modelType.getMethod("parent").invoke(base));
    }

    private static Object geometry(Class<?> modelType, InvocationHandler bake) throws ReflectiveOperationException {
        Class<?> geometryType = modelType.getMethod("geometry").getReturnType();
        return Proxy.newProxyInstance(modelType.getClassLoader(), new Class<?>[] { geometryType }, (proxy, method,
                arguments) -> switch (method.getName()) {
                    case "bake" -> bake.invoke(proxy, method, arguments);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "Octo custom model geometry";
                    default -> method.isDefault() ? InvocationHandler.invokeDefault(proxy, method, arguments) : null;
                });
    }

    // ----------------------------------------------------------------- OBJ

    private static Object obj(JsonObject json, Class<?> modelType, Method original)
            throws ReflectiveOperationException {
        String model = json.get("model").getAsString();
        boolean flipV = json.has("flip_v") && json.get("flip_v").getAsBoolean();
        JsonObject plain = json.deepCopy();
        plain.remove("loader");
        plain.remove("model");
        plain.remove("flip_v");
        Object base = read(original, plain.toString());
        return withGeometry(base, modelType, geometry(modelType,
                (proxy, method, arguments) -> bakeObj(modelType, model, flipV, arguments)));
    }

    /** {@code bake(textureSlots, baker, modelState, debugName)} for a mesh. */
    private static Object bakeObj(Class<?> modelType, String model, boolean flipV, Object[] arguments)
            throws Throwable {
        Object slots = arguments[0];
        Object baker = arguments[1];
        Object state = arguments[2];
        Object debugName = arguments[3];
        ClassLoader loader = modelType.getClassLoader();
        Class<?> collection = Class.forName("net.minecraft.client.resources.model.geometry.QuadCollection$Builder",
                true, loader);
        Object builder = collection.getConstructor().newInstance();
        Method addUnculled = null;

        for (Method method : collection.getMethods()) {
            if (method.getName().equals("addUnculledFace")) {
                addUnculled = method;
            }
        }

        Game game = Game.of(loader);
        Object matrix = game.matrix(state);

        for (ObjMesh.Face face : mesh(loader, model, flipV)) {
            Object sprite = game.sprite(face.texture(), slots, baker, debugName);
            int[] vertices = new int[32];
            float[][] corners = new float[4][];

            for (int corner = 0; corner < 4; corner++) {
                float[] p = game.transform(matrix, face.positions()[corner]);
                corners[corner] = p;
                float u = game.u(sprite, face.uvs()[corner][0]);
                float v = game.v(sprite, face.uvs()[corner][1]);
                int at = corner * 8;
                vertices[at] = Float.floatToRawIntBits(p[0]);
                vertices[at + 1] = Float.floatToRawIntBits(p[1]);
                vertices[at + 2] = Float.floatToRawIntBits(p[2]);
                vertices[at + 3] = 0xFFFFFFFF;
                vertices[at + 4] = Float.floatToIntBits(u);
                vertices[at + 5] = Float.floatToIntBits(v);
            }

            Object direction = game.facing(corners);
            addUnculled.invoke(builder, LegacyQuads.make(vertices, -1, direction, sprite, true));
        }

        return collection.getMethod("build").invoke(builder);
    }

    /** A mesh's faces, read once per resource reload. */
    private static List<ObjMesh.Face> mesh(ClassLoader loader, String model, boolean flipV) throws Throwable {
        Object manager = Game.of(loader).resourceManager();

        if (manager != meshesFrom) {
            MESHES.clear();
            meshesFrom = manager;
        }

        String key = model + (flipV ? "|flip" : "");
        List<ObjMesh.Face> cached = MESHES.get(key);

        if (cached != null) {
            return cached;
        }

        Game game = Game.of(loader);
        String obj = game.text(manager, model);

        if (obj == null) {
            LOG.warn("the mesh {} is not in any pack; its model is drawn without it", model);
            MESHES.put(key, List.of());
            return List.of();
        }

        int slash = model.lastIndexOf('/');
        String folder = slash < 0 ? model.substring(0, model.indexOf(':') + 1) : model.substring(0, slash + 1);
        List<ObjMesh.Face> faces = ObjMesh.parse(obj, name -> {
            try {
                return game.text(manager, folder + name);
            } catch (Throwable e) {
                return null;
            }
        }, flipV);
        MESHES.put(key, faces);
        return faces;
    }

    // ----------------------------------------------------------- composite

    /** A part of a composite model: its own geometry and textures. */
    private record Part(Object geometry, Object textures, Object parent) {
    }

    private static Object composite(JsonObject json, Class<?> modelType, Method original) throws Throwable {
        JsonObject children = json.getAsJsonObject("children");
        JsonObject textures = json.has("textures") && json.get("textures").isJsonObject()
                ? json.getAsJsonObject("textures") : new JsonObject();
        List<Part> parts = new ArrayList<>();

        if (children != null) {
            for (Map.Entry<String, JsonElement> child : children.entrySet()) {
                if (!child.getValue().isJsonObject()) {
                    continue;
                }

                // A part sees the whole model's textures, its own first.
                JsonObject part = child.getValue().getAsJsonObject().deepCopy();
                JsonObject merged = textures.deepCopy();

                if (part.has("textures") && part.get("textures").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> own : part.getAsJsonObject("textures").entrySet()) {
                        merged.add(own.getKey(), own.getValue());
                    }
                }

                part.add("textures", merged);
                part.remove("render_type");
                Object read = fromStream(new StringReader(part.toString()), modelType);
                parts.add(new Part(modelType.getMethod("geometry").invoke(read),
                        modelType.getMethod("textureSlots").invoke(read), modelType.getMethod("parent").invoke(read)));
            }
        }

        JsonObject plain = json.deepCopy();
        plain.remove("loader");
        plain.remove("children");
        plain.remove("visibility");
        plain.remove("item_render_order");
        Object base = read(original, plain.toString());
        return withGeometry(base, modelType, geometry(modelType,
                (proxy, method, arguments) -> bakeComposite(modelType, parts, arguments)));
    }

    private static Object bakeComposite(Class<?> modelType, List<Part> parts, Object[] arguments) throws Throwable {
        ClassLoader loader = modelType.getClassLoader();
        Object baker = arguments[1];
        Object state = arguments[2];
        Object debugName = arguments[3];
        Class<?> collection = Class.forName("net.minecraft.client.resources.model.geometry.QuadCollection$Builder",
                true, loader);
        Class<?> quads = Class.forName("net.minecraft.client.resources.model.geometry.QuadCollection", true, loader);
        Object builder = collection.getConstructor().newInstance();
        Method addAll = collection.getMethod("addAll", quads);
        Game game = Game.of(loader);

        for (Part part : parts) {
            Object baked = null;

            if (part.geometry() != null) {
                Object slots = game.resolve(part.textures(), debugName);
                baked = game.geometryBake.invoke(part.geometry(), slots, baker, state, debugName);
            } else if (part.parent() != null) {
                baked = game.bakeModel(baker, part.parent(), state);
            }

            if (baked != null) {
                addAll.invoke(builder, baked);
            }
        }

        return collection.getMethod("build").invoke(builder);
    }

    // -------------------------------------------------------------- the game

    /** The game's classes this needs, reached by reflection. */
    private static final class Game {
        private static final Map<ClassLoader, Game> GAMES = new ConcurrentHashMap<>();

        final Method getMaterial;
        final Method materials;
        final Method materialsGet;
        final Method reportMissing;
        final Method bakedSprite;
        final java.lang.reflect.Constructor<?> material;
        final Method parse;
        final Method getU;
        final Method getV;
        final Method transformation;
        final Method getMatrix;
        final Method transformPosition;
        final java.lang.reflect.Constructor<?> vector;
        final Method nearest;
        final Method minecraft;
        final Method resourceManager;
        final Method getResource;
        final Method open;
        final java.lang.reflect.Constructor<?> resolver;
        final Method addLast;
        final Method resolve;
        final Method geometryBake;
        final Method getModel;
        final Class<?> vectorType;

        static Game of(ClassLoader loader) {
            return GAMES.computeIfAbsent(loader, Game::new);
        }

        Game(ClassLoader loader) {
            try {
                Class<?> slots = type(loader, "net.minecraft.client.resources.model.sprite.TextureSlots");
                Class<?> baker = type(loader, "net.minecraft.client.resources.model.ModelBaker");
                Class<?> materialBaker = type(loader, "net.minecraft.client.resources.model.sprite.MaterialBaker");
                Class<?> materialType = type(loader, "net.minecraft.client.resources.model.sprite.Material");
                Class<?> debugName = type(loader, "net.minecraft.client.resources.model.ModelDebugName");
                Class<?> identifier = type(loader, "net.minecraft.resources.Identifier");
                Class<?> sprite = type(loader, "net.minecraft.client.renderer.texture.TextureAtlasSprite");
                Class<?> modelState = type(loader, "net.minecraft.client.renderer.block.dispatch.ModelState");
                Class<?> transformationType = type(loader, "com.mojang.math.Transformation");
                Class<?> matrix = type(loader, "org.joml.Matrix4fc");
                Class<?> direction = type(loader, "net.minecraft.core.Direction");
                Class<?> client = type(loader, "net.minecraft.client.Minecraft");
                Class<?> provider = type(loader, "net.minecraft.server.packs.resources.ResourceProvider");
                Class<?> resource = type(loader, "net.minecraft.server.packs.resources.Resource");
                Class<?> resolverType = type(loader, "net.minecraft.client.resources.model.sprite.TextureSlots$Resolver");
                Class<?> data = type(loader, "net.minecraft.client.resources.model.sprite.TextureSlots$Data");

                getMaterial = slots.getMethod("getMaterial", String.class);
                materials = baker.getMethod("materials");
                materialsGet = materialBaker.getMethod("get", materialType, debugName);
                reportMissing = materialBaker.getMethod("reportMissingReference", String.class, debugName);
                bakedSprite = type(loader, "net.minecraft.client.resources.model.sprite.Material$Baked")
                        .getMethod("sprite");
                material = materialType.getConstructor(identifier);
                parse = identifier.getMethod("parse", String.class);
                getU = sprite.getMethod("getU", float.class);
                getV = sprite.getMethod("getV", float.class);
                transformation = modelState.getMethod("transformation");
                getMatrix = transformationType.getMethod("getMatrix");
                vectorType = type(loader, "org.joml.Vector3f");
                transformPosition = matrix.getMethod("transformPosition", vectorType);
                vector = vectorType.getConstructor(float.class, float.class, float.class);
                nearest = direction.getMethod("getApproximateNearest", float.class, float.class, float.class);
                minecraft = client.getMethod("getInstance");
                resourceManager = client.getMethod("getResourceManager");
                getResource = provider.getMethod("getResource", identifier);
                open = resource.getMethod("open");
                resolver = resolverType.getConstructor();
                addLast = resolverType.getMethod("addLast", data);
                resolve = resolverType.getMethod("resolve", debugName);
                geometryBake = type(loader, "net.minecraft.client.resources.model.geometry.UnbakedGeometry")
                        .getMethod("bake", slots, baker, modelState, debugName);
                getModel = baker.getMethod("getModel", identifier);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("this game's models cannot be bridged", e);
            }
        }

        private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
            return Class.forName(name, true, loader);
        }

        Object resourceManager() throws ReflectiveOperationException {
            return resourceManager.invoke(minecraft.invoke(null));
        }

        /** A file's text from the packs, or {@code null} when none has it. */
        String text(Object manager, String location) throws ReflectiveOperationException, IOException {
            java.util.Optional<?> found = (java.util.Optional<?>) getResource.invoke(manager, parse.invoke(null,
                    location));

            if (found.isEmpty()) {
                return null;
            }

            try (InputStream in = (InputStream) open.invoke(found.get())) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        /** The sprite for a face's texture: a slot ({@code #0}) or a texture name. */
        Object sprite(String texture, Object slots, Object baker, Object debugName) throws ReflectiveOperationException {
            Object materialBaker = materials.invoke(baker);
            Object baked;

            if (texture == null) {
                baked = reportMissing.invoke(materialBaker, "missing", debugName);
            } else if (texture.startsWith("#")) {
                Object slot = getMaterial.invoke(slots, texture.substring(1));
                baked = slot == null ? reportMissing.invoke(materialBaker, texture.substring(1), debugName)
                        : materialsGet.invoke(materialBaker, slot, debugName);
            } else {
                baked = materialsGet.invoke(materialBaker, material.newInstance(parse.invoke(null, texture)),
                        debugName);
            }

            return bakedSprite.invoke(baked);
        }

        Object matrix(Object state) throws ReflectiveOperationException {
            return getMatrix.invoke(transformation.invoke(state));
        }

        /** A position turned the way the block state turns its model: about the block's middle. */
        float[] transform(Object matrix, float[] p) throws ReflectiveOperationException {
            Object v = vector.newInstance(p[0] - 0.5f, p[1] - 0.5f, p[2] - 0.5f);
            transformPosition.invoke(matrix, v);
            return new float[] { vectorType.getField("x").getFloat(v) + 0.5f,
                    vectorType.getField("y").getFloat(v) + 0.5f, vectorType.getField("z").getFloat(v) + 0.5f };
        }

        float u(Object sprite, float u) throws ReflectiveOperationException {
            return (float) getU.invoke(sprite, u);
        }

        float v(Object sprite, float v) throws ReflectiveOperationException {
            return (float) getV.invoke(sprite, v);
        }

        /** The face a quad looks towards, from its corners. */
        Object facing(float[][] c) throws ReflectiveOperationException {
            float[] n = ObjMesh.normal(c);
            return nearest.invoke(null, n[0], n[1], n[2]);
        }

        Object resolve(Object textureData, Object debugName) throws ReflectiveOperationException {
            Object r = resolver.newInstance();
            addLast.invoke(r, textureData);
            return resolve.invoke(r, debugName);
        }

        /** Another model's shape, by name, as the game bakes it: for a part that is only a parent. */
        Object bakeModel(Object baker, Object parent, Object state) {
            try {
                Object resolved = getModel.invoke(baker, parent);
                Object slots = resolved.getClass().getMethod("getTopTextureSlots").invoke(resolved);
                Method bake = null;

                for (Method method : resolved.getClass().getMethods()) {
                    if (method.getName().equals("bakeTopGeometry") && method.getParameterCount() == 3) {
                        bake = method;
                    }
                }

                return bake == null ? null : bake.invoke(resolved, slots, baker, state);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }
}
