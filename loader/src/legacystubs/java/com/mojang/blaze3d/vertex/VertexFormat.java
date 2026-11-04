package com.mojang.blaze3d.vertex;

/**
 * Compile-time shape of the vertex format the game had until 26.x; at runtime
 * it is the class added back from the release a mod was built for.
 */
public class VertexFormat {
    public enum Mode {
        LINES, LINE_STRIP, DEBUG_LINES, DEBUG_LINE_STRIP, TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN, QUADS
    }
}
