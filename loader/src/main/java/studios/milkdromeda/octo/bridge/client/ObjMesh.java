package studios.milkdromeda.octo.bridge.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A Wavefront OBJ mesh, read the way NeoForge's and Forge's OBJ model loader
 * reads one for a block or item model: positions in block units, texture
 * coordinates from 0 to 1, and each face's texture from its material's
 * {@code map_Kd} in the {@code mtllib} beside it — a texture name, or
 * {@code #slot} for one of the model's own textures.
 *
 * <p>Faces become quads, as the game draws: a triangle repeats its last
 * corner, and a larger polygon is split into a fan of them.
 */
final class ObjMesh {
    /** One quad: its texture, and four corners' positions and texture coordinates. */
    record Face(String texture, float[][] positions, float[][] uvs) {
    }

    private ObjMesh() {
    }

    /**
     * @param obj     the OBJ file's text
     * @param sibling a file beside it, by name, as text; {@code null} when there is none
     * @param flipV   whether texture coordinates run bottom-up, as {@code "flip_v": true} says
     */
    static List<Face> parse(String obj, Function<String, String> sibling, boolean flipV) {
        List<float[]> positions = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        Map<String, String> materials = new HashMap<>();
        List<Face> faces = new ArrayList<>();
        String texture = null;

        for (String raw : obj.split("\\R")) {
            String line = raw.strip();
            int comment = line.indexOf('#');

            // A '#' starts a comment, except in a material's "#slot", which is in the .mtl file.
            if (comment >= 0) {
                line = line.substring(0, comment).strip();
            }

            if (line.isEmpty()) {
                continue;
            }

            String[] parts = line.split("\\s+");

            switch (parts[0]) {
                case "v" -> positions.add(new float[] { number(parts, 1), number(parts, 2), number(parts, 3) });
                case "vt" -> {
                    float v = number(parts, 2);
                    uvs.add(new float[] { number(parts, 1), flipV ? 1 - v : v });
                }
                case "mtllib" -> {
                    for (int i = 1; i < parts.length; i++) {
                        String text = sibling.apply(parts[i]);

                        if (text != null) {
                            materials.putAll(materials(text));
                        }
                    }
                }
                case "usemtl" -> texture = parts.length > 1 ? materials.getOrDefault(parts[1], null) : null;
                case "f" -> face(parts, positions, uvs, texture, faces);
                default -> {
                    // Normals, groups, smoothing: the game works these out for itself.
                }
            }
        }

        return faces;
    }

    /** Materials by name, each its {@code map_Kd} texture. */
    static Map<String, String> materials(String mtl) {
        Map<String, String> out = new HashMap<>();
        String current = null;

        for (String raw : mtl.split("\\R")) {
            String line = raw.strip();

            if (line.startsWith("newmtl ")) {
                current = line.substring("newmtl ".length()).strip();
            } else if (line.startsWith("map_Kd ") && current != null) {
                out.put(current, line.substring("map_Kd ".length()).strip());
            }
        }

        return out;
    }

    private static void face(String[] parts, List<float[]> positions, List<float[]> uvs, String texture,
            List<Face> faces) {
        int corners = parts.length - 1;

        if (corners < 3) {
            return;
        }

        float[][] p = new float[corners][];
        float[][] t = new float[corners][];

        for (int i = 0; i < corners; i++) {
            String[] indices = parts[i + 1].split("/");
            p[i] = at(positions, indices[0]);
            t[i] = indices.length > 1 && !indices[1].isEmpty() ? at(uvs, indices[1]) : new float[] { 0, 0 };
        }

        if (corners == 4) {
            faces.add(new Face(texture, p, t));
            return;
        }

        // A triangle, or a fan of them: each one a quad with its last corner twice.
        for (int i = 1; i + 1 < corners; i++) {
            faces.add(new Face(texture, new float[][] { p[0], p[i], p[i + 1], p[i + 1] },
                    new float[][] { t[0], t[i], t[i + 1], t[i + 1] }));
        }
    }

    /**
     * Which way a quad faces: the cross product of its diagonals, which for a
     * triangle (last corner twice) is that of two of its edges. Corners run
     * anticlockwise seen from the front, as in OBJ files and the game's quads.
     */
    static float[] normal(float[][] c) {
        float ax = c[2][0] - c[0][0];
        float ay = c[2][1] - c[0][1];
        float az = c[2][2] - c[0][2];
        float bx = c[3][0] - c[1][0];
        float by = c[3][1] - c[1][1];
        float bz = c[3][2] - c[1][2];
        return new float[] { ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx };
    }

    /** An OBJ index: counted from 1, or from the end when negative. */
    private static float[] at(List<float[]> list, String index) {
        int i = Integer.parseInt(index);
        return list.get(i > 0 ? i - 1 : list.size() + i);
    }

    private static float number(String[] parts, int index) {
        return index < parts.length ? Float.parseFloat(parts[index]) : 0f;
    }
}
