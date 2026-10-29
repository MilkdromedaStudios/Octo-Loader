package studios.milkdromeda.octo.bridge.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A baked quad as code from before 26.x reads and makes one.
 *
 * <p>Until 26.x a quad was its vertex data — 32 ints, eight per corner:
 * position, colour, texture coordinates, light, normal — with a tint, a
 * face, a sprite and a shading flag beside it, and mods that reshape models
 * read and wrote those ints: Create swaps a water wheel's planks for the
 * wood it is made of, scrolls its belts and shifts its casings' textures that
 * way. 26.x's quad is a record of four positions, four packed texture
 * coordinates, a face and a material. The old reads are answered from the
 * record, and the old constructor builds one, its material made the way the
 * game makes a block model's.
 */
public final class LegacyQuads {
    private static final int STRIDE = 8;
    private static final int WHITE = 0xFFFFFFFF;

    private static final Map<ClassLoader, Game> GAMES = new ConcurrentHashMap<>();

    private LegacyQuads() {
    }

    /** {@code quad.getVertices()}: the old 32-int vertex data, from the record. */
    public static int[] vertices(Object quad) {
        int[] out = new int[STRIDE * 4];

        if (quad == null) {
            return out;
        }

        Game game = game(quad);

        try {
            int normal = packNormal(game.direction.invoke(quad), game);

            for (int corner = 0; corner < 4; corner++) {
                Object position = game.position.invoke(quad, corner);
                long uv = (long) game.packedUV.invoke(quad, corner);
                int at = corner * STRIDE;
                out[at] = Float.floatToRawIntBits((float) game.x.invoke(position));
                out[at + 1] = Float.floatToRawIntBits((float) game.y.invoke(position));
                out[at + 2] = Float.floatToRawIntBits((float) game.z.invoke(position));
                out[at + 3] = WHITE;
                out[at + 4] = (int) (uv >>> 32);
                out[at + 5] = (int) uv;
                out[at + 6] = 0;
                out[at + 7] = normal;
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not read a quad's vertices", e);
        }

        return out;
    }

    /** {@code quad.getSprite()}. */
    public static Object sprite(Object quad) {
        return quad == null ? null : game(quad).material(quad, "sprite");
    }

    /** {@code quad.getTintIndex()}. */
    public static int tintIndex(Object quad) {
        return quad != null && game(quad).material(quad, "tintIndex") instanceof Integer tint ? tint : -1;
    }

    /** {@code quad.isShade()}: shaded by its face unless the material says otherwise. */
    public static boolean shade(Object quad) {
        return quad == null || game(quad).material(quad, "shadeDirectionOverride") == null;
    }

    /** {@code quad.getDirection()}. */
    public static Object direction(Object quad) {
        try {
            return quad == null ? null : game(quad).direction.invoke(quad);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** {@code new BakedQuad(vertices, tintIndex, direction, sprite, shade)}, and with the ambient occlusion flag. */
    public static Object make(int[] vertices, int tintIndex, Object direction, Object sprite, boolean shade) {
        ClassLoader loader = loaderOf(direction, sprite);

        if (loader == null) {
            throw new IllegalStateException("a quad needs a face or a sprite to be made");
        }

        Game game = GAMES.computeIfAbsent(loader, Game::new);

        try {
            Object[] positions = new Object[4];
            long[] uvs = new long[4];

            for (int corner = 0; corner < 4; corner++) {
                int at = corner * STRIDE;
                positions[corner] = game.vector.newInstance(Float.intBitsToFloat(vertices[at]),
                        Float.intBitsToFloat(vertices[at + 1]), Float.intBitsToFloat(vertices[at + 2]));
                uvs[corner] = (long) vertices[at + 4] << 32 | (vertices[at + 5] & 0xFFFFFFFFL);
            }

            Object face = direction != null ? direction : game.up;
            Object material = game.materialOf(sprite, tintIndex, shade);
            return game.quad.newInstance(positions[0], positions[1], positions[2], positions[3], uvs[0], uvs[1],
                    uvs[2], uvs[3], face, material);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not make a quad", e);
        }
    }

    public static Object make(int[] vertices, int tintIndex, Object direction, Object sprite, boolean shade,
            boolean ambientOcclusion) {
        return make(vertices, tintIndex, direction, sprite, shade);
    }

    /** The old packed normal: each axis a signed byte, x lowest. */
    private static int packNormal(Object direction, Game game) throws ReflectiveOperationException {
        if (direction == null) {
            return 0;
        }

        int x = (int) game.stepX.invoke(direction);
        int y = (int) game.stepY.invoke(direction);
        int z = (int) game.stepZ.invoke(direction);
        return (x * 127 & 0xFF) | (y * 127 & 0xFF) << 8 | (z * 127 & 0xFF) << 16;
    }

    private static ClassLoader loaderOf(Object... objects) {
        for (Object object : objects) {
            if (object != null && object.getClass().getClassLoader() != null) {
                return object.getClass().getClassLoader();
            }
        }

        return null;
    }

    private static Game game(Object anything) {
        return GAMES.computeIfAbsent(anything.getClass().getClassLoader(), Game::new);
    }

    /** The game's quad, material and vector classes, reached by reflection. */
    private static final class Game {
        final Constructor<?> quad;
        final Constructor<?> vector;
        final Method position;
        final Method packedUV;
        final Method direction;
        final Method materialInfo;
        final Method x;
        final Method y;
        final Method z;
        final Method stepX;
        final Method stepY;
        final Method stepZ;
        final Object up;
        final Method materialOf;
        final Constructor<?> bakedMaterial;
        final Object cutout;
        final Map<String, Method> materialParts = new ConcurrentHashMap<>();

        Game(ClassLoader loader) {
            try {
                Class<?> quadType = Class.forName("net.minecraft.client.resources.model.geometry.BakedQuad", true, loader);
                Class<?> info = Class.forName("net.minecraft.client.resources.model.geometry.BakedQuad$MaterialInfo",
                        true, loader);
                Class<?> vectorc = Class.forName("org.joml.Vector3fc", true, loader);
                Class<?> directionType = Class.forName("net.minecraft.core.Direction", true, loader);
                Class<?> baked = Class.forName("net.minecraft.client.resources.model.sprite.Material$Baked", true,
                        loader);
                Class<?> sprite = Class.forName("net.minecraft.client.renderer.texture.TextureAtlasSprite", true,
                        loader);
                Class<?> transparency = Class.forName("com.mojang.blaze3d.platform.Transparency", true, loader);

                quad = quadType.getConstructor(vectorc, vectorc, vectorc, vectorc, long.class, long.class, long.class,
                        long.class, directionType, info);
                vector = Class.forName("org.joml.Vector3f", true, loader).getConstructor(float.class, float.class,
                        float.class);
                position = quadType.getMethod("position", int.class);
                packedUV = quadType.getMethod("packedUV", int.class);
                direction = quadType.getMethod("direction");
                materialInfo = quadType.getMethod("materialInfo");
                x = vectorc.getMethod("x");
                y = vectorc.getMethod("y");
                z = vectorc.getMethod("z");
                stepX = directionType.getMethod("getStepX");
                stepY = directionType.getMethod("getStepY");
                stepZ = directionType.getMethod("getStepZ");
                up = directionType.getField("UP").get(null);
                materialOf = info.getMethod("of", baked, transparency, int.class, directionType, int.class);
                bakedMaterial = baked.getConstructor(sprite, boolean.class);
                cutout = transparency.getField("TRANSPARENT").get(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("this game's quads cannot be bridged", e);
            }
        }

        Object material(Object quadValue, String part) {
            try {
                Object info = materialInfo.invoke(quadValue);

                if (info == null) {
                    return null;
                }

                Method accessor = materialParts.computeIfAbsent(part, name -> {
                    try {
                        return info.getClass().getMethod(name);
                    } catch (NoSuchMethodException e) {
                        return null;
                    }
                });
                return accessor == null ? null : accessor.invoke(info);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        /** A material as the game makes a block model's: cut out, lit by the face unless unshaded. */
        Object materialOf(Object sprite, int tintIndex, boolean shade) throws ReflectiveOperationException {
            return materialOf.invoke(null, bakedMaterial.newInstance(sprite, false), cutout, tintIndex,
                    shade ? null : up, 0);
        }
    }
}
