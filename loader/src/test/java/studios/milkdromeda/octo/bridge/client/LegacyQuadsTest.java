package studios.milkdromeda.octo.bridge.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;

/** A quad as code from before 26.x reads and builds one: 32 ints of vertex data, made into 26.x's record. */
class LegacyQuadsTest {
    /** The old layout: x, y, z, colour, u, v, light, normal for each of four corners. */
    private static int[] oldVertices() {
        int[] vertices = new int[32];
        float[][] corners = { { 0, 1, 0 }, { 0, 1, 1 }, { 1, 1, 1 }, { 1, 1, 0 } };
        float[][] uvs = { { 0.25f, 0.5f }, { 0.25f, 0.75f }, { 0.5f, 0.75f }, { 0.5f, 0.5f } };

        for (int corner = 0; corner < 4; corner++) {
            int at = corner * 8;
            vertices[at] = Float.floatToRawIntBits(corners[corner][0]);
            vertices[at + 1] = Float.floatToRawIntBits(corners[corner][1]);
            vertices[at + 2] = Float.floatToRawIntBits(corners[corner][2]);
            vertices[at + 3] = -1;
            vertices[at + 4] = Float.floatToRawIntBits(uvs[corner][0]);
            vertices[at + 5] = Float.floatToRawIntBits(uvs[corner][1]);
            vertices[at + 7] = 127 << 8;
        }

        return vertices;
    }

    @Test
    @DisplayName("a quad made from old vertex data reads back the same positions, texture coordinates and normal")
    void roundTrip() {
        TextureAtlasSprite planks = new TextureAtlasSprite("spruce_planks");
        int[] vertices = oldVertices();
        Object quad = LegacyQuads.make(vertices, 2, Direction.UP, planks, true);

        BakedQuad made = (BakedQuad) quad;
        assertEquals(1f, made.position(2).x());
        assertEquals(0.75f, Float.intBitsToFloat((int) made.packedUV(1)), "v is the low half of the packed pair");
        assertArrayEquals(vertices, LegacyQuads.vertices(quad));
    }

    @Test
    @DisplayName("the old sprite, tint, face and shading flag are read from the record's material")
    void materialIsRead() {
        TextureAtlasSprite planks = new TextureAtlasSprite("oak_planks");
        Object shaded = LegacyQuads.make(oldVertices(), 0, Direction.NORTH, planks, true);
        Object flat = LegacyQuads.make(oldVertices(), -1, Direction.NORTH, planks, false);

        assertSame(planks, LegacyQuads.sprite(shaded));
        assertEquals(0, LegacyQuads.tintIndex(shaded));
        assertEquals(-1, LegacyQuads.tintIndex(flat));
        assertSame(Direction.NORTH, LegacyQuads.direction(shaded));
        assertTrue(LegacyQuads.shade(shaded));
        assertFalse(LegacyQuads.shade(flat), "an unshaded quad keeps the game from shading it by its face");
    }

    @Test
    @DisplayName("a quad with no face still gets a normal of none, and a null quad reads as empty")
    void edges() {
        assertArrayEquals(new int[32], LegacyQuads.vertices(null));
        assertEquals(-1, LegacyQuads.tintIndex(null));
    }
}
