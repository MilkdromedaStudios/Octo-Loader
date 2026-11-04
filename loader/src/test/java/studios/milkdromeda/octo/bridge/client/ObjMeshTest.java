package studios.milkdromeda.octo.bridge.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Wavefront meshes as NeoForge's OBJ model loader reads them for Create's blaze burner, flywheel and the rest. */
class ObjMeshTest {
    private static final String MTL = """
            # Blender MTL File: 'engine.blend'
            newmtl m_0
            map_Kd #0

            newmtl m_1
            map_Kd create:block/flywheel_side
            """;

    private static final String OBJ = """
            # Blender 4.2.0
            mtllib flywheel.mtl
            o cube
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            v 0.5 0.5 1
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            vn 0 0 -1
            usemtl m_0
            f 1/1/1 2/2/1 3/3/1 4/4/1
            usemtl m_1
            f 1/1 2/2 5/3
            f -5/1/1 -4/2/1 -3/3/1 -2/4/1 -1/1/1
            usemtl nothing
            f 1//1 2//1 3//1
            """;

    @Test
    @DisplayName("faces become quads with their material's texture: a slot or a texture name")
    void facesAndMaterials() {
        List<ObjMesh.Face> faces = ObjMesh.parse(OBJ, name -> name.equals("flywheel.mtl") ? MTL : null, false);

        // A quad, a triangle, a pentagon (three triangles in a fan) and a triangle with no texture coordinates.
        assertEquals(1 + 1 + 3 + 1, faces.size());
        assertEquals("#0", faces.get(0).texture());
        assertEquals("create:block/flywheel_side", faces.get(1).texture());
        assertNull(faces.get(5).texture(), "a material the library does not have");

        assertArrayEquals(new float[] { 1, 1, 0 }, faces.get(0).positions()[2]);
        assertArrayEquals(new float[] { 1, 1 }, faces.get(0).uvs()[2]);
        // A triangle repeats its last corner.
        assertArrayEquals(faces.get(1).positions()[2], faces.get(1).positions()[3]);
        // Negative indices count from the end.
        assertArrayEquals(new float[] { 0, 0, 0 }, faces.get(2).positions()[0]);
        assertArrayEquals(new float[] { 0, 0 }, faces.get(5).uvs()[1], "no coordinates: the corner of the texture");
    }

    @Test
    @DisplayName("flip_v turns texture coordinates the right way up")
    void flipV() {
        List<ObjMesh.Face> faces = ObjMesh.parse(OBJ, name -> MTL, true);
        assertArrayEquals(new float[] { 0, 1 }, faces.get(0).uvs()[0]);
        assertArrayEquals(new float[] { 1, 0 }, faces.get(0).uvs()[2]);
    }

    @Test
    @DisplayName("a quad's normal points out of its anticlockwise side, a triangle's too")
    void normals() {
        float[][] square = { { 0, 0, 0 }, { 1, 0, 0 }, { 1, 1, 0 }, { 0, 1, 0 } };
        float[] n = ObjMesh.normal(square);
        assertTrue(n[2] > 0 && n[0] == 0 && n[1] == 0, "towards +z");

        float[][] triangle = { { 0, 0, 0 }, { 0, 0, 1 }, { 1, 0, 0 }, { 1, 0, 0 } };
        float[] t = ObjMesh.normal(triangle);
        assertTrue(t[1] > 0, "towards +y");
    }

    @Test
    @DisplayName("a material library is read by material name")
    void materials() {
        assertEquals(Map.of("m_0", "#0", "m_1", "create:block/flywheel_side"), ObjMesh.materials(MTL));
    }
}
