package com.mojang.blaze3d.vertex;

/**
 * The upload a mod ended its own drawing with until 26.x: the shape
 * {@link Tesselator}'s buffer recorded, added to the frame being drawn.
 */
public class BufferUploader {
    public BufferUploader() {
    }

    public static void reset() {
    }

    public static void invalidate() {
    }

    public static void drawWithShader(MeshData mesh) {
        Tesselator.drawBuilt();
    }

    public static void draw(MeshData mesh) {
        Tesselator.drawBuilt();
    }
}
