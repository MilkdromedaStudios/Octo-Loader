package com.mojang.blaze3d.vertex;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/**
 * The tesselator screens drew their own shapes with until 26.x, drawing them
 * into the frame being collected.
 *
 * <p>A mod drew a rounded box or a gradient border by taking the tesselator's
 * buffer, adding coloured vertices and handing the result to
 * {@link BufferUploader#drawWithShader}. 26.x has neither class: drawing a
 * screen is collecting what to draw, and the game uploads it later. Catnip
 * draws every box of every Create menu so, and the copy added back from the
 * mod's release had never been started, so each menu stopped at its first box.
 *
 * <p>Here the buffer records the vertices, and the upload adds them to the
 * frame being drawn as one coloured shape of the game's, where the screen's
 * {@link GuiGraphics} last pointed. Coloured shapes only: a textured one names
 * its texture through the render system, which is gone, so it is left out.
 */
public class Tesselator {
    private static Tesselator instance;

    public Tesselator(int capacity) {
    }

    public Tesselator() {
        this(0);
    }

    public static void init() {
        if (instance == null) {
            instance = new Tesselator();
        }
    }

    public static Tesselator getInstance() {
        init();
        return instance;
    }

    /** The format is the game's now: Octo moves mods' references to the old one there. */
    public BufferBuilder begin(VertexFormat.Mode mode, com.mojang.renderpearl.api.vertex.VertexFormat format) {
        return new Recorder(((Enum<?>) mode).name());
    }

    public void clear() {
    }

    /** The shape last built, which the next upload draws. */
    private static Recorder built;

    static void drawBuilt() {
        Recorder recorder = built;
        built = null;

        if (recorder != null) {
            recorder.draw();
        }
    }

    private static final ByteBufferBuilder UNWRITTEN = new ByteBufferBuilder(16);

    /** A buffer that keeps the vertices it is given. */
    static final class Recorder extends BufferBuilder {
        private final String mode;
        private float[] positions = new float[64];
        private int[] colors = new int[32];
        private int count;
        private boolean textured;

        Recorder(String mode) {
            // The game's buffer only holds this one's place: nothing is written to it.
            super(UNWRITTEN, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
            this.mode = mode;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (count * 2 + 2 > positions.length) {
                positions = Arrays.copyOf(positions, positions.length * 2);
                colors = Arrays.copyOf(colors, colors.length * 2);
            }

            positions[count * 2] = x;
            positions[count * 2 + 1] = y;
            colors[count] = -1;
            count++;
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                float normalX, float normalY, float normalZ) {
            addVertex(x, y, z);
            setColor(color);
            textured = true;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return setColor(alpha << 24 | red << 16 | green << 8 | blue);
        }

        @Override
        public VertexConsumer setColor(int argb) {
            if (count > 0) {
                colors[count - 1] = argb;
            }

            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            textured = true;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setOverlay(int overlay) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setLight(int light) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        @Override
        public MeshData build() {
            built = this;
            return null;
        }

        @Override
        public MeshData buildOrThrow() {
            return build();
        }

        /** Adds what was recorded to the frame, as quads. */
        void draw() {
            GuiGraphicsExtractor out = GuiGraphics.octo$current();

            if (out == null || textured || count < 3) {
                return;
            }

            int[] order = quads();

            if (order.length == 0) {
                return;
            }

            float[] xy = new float[order.length * 2];
            int[] argb = new int[order.length];

            for (int i = 0; i < order.length; i++) {
                xy[i * 2] = positions[order[i] * 2];
                xy[i * 2 + 1] = positions[order[i] * 2 + 1];
                argb[i] = colors[order[i]];
            }

            Matrix3x2f pose = new Matrix3x2f(out.pose());
            add(out, new Shape(xy, argb, pose, bounds(xy, pose)));
        }

        /** The vertices in the order the game's quads take them. */
        private int[] quads() {
            return switch (mode) {
                case "QUADS" -> range(count - count % 4);
                case "TRIANGLES" -> {
                    int triangles = count / 3;
                    int[] out = new int[triangles * 4];

                    for (int t = 0; t < triangles; t++) {
                        out[t * 4] = t * 3;
                        out[t * 4 + 1] = t * 3 + 1;
                        out[t * 4 + 2] = t * 3 + 2;
                        out[t * 4 + 3] = t * 3 + 2;
                    }

                    yield out;
                }
                case "TRIANGLE_STRIP" -> {
                    int[] out = new int[(count - 2) * 4];

                    for (int t = 0; t < count - 2; t++) {
                        boolean even = t % 2 == 0;
                        out[t * 4] = even ? t : t + 1;
                        out[t * 4 + 1] = even ? t + 1 : t;
                        out[t * 4 + 2] = t + 2;
                        out[t * 4 + 3] = t + 2;
                    }

                    yield out;
                }
                case "TRIANGLE_FAN" -> {
                    int[] out = new int[(count - 2) * 4];

                    for (int t = 0; t < count - 2; t++) {
                        out[t * 4] = 0;
                        out[t * 4 + 1] = t + 1;
                        out[t * 4 + 2] = t + 2;
                        out[t * 4 + 3] = t + 2;
                    }

                    yield out;
                }
                default -> new int[0];
            };
        }

        private static int[] range(int length) {
            int[] out = new int[length];

            for (int i = 0; i < length; i++) {
                out[i] = i;
            }

            return out;
        }

        private static ScreenRectangle bounds(float[] xy, Matrix3x2f pose) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            org.joml.Vector2f point = new org.joml.Vector2f();

            for (int i = 0; i < xy.length; i += 2) {
                pose.transformPosition(xy[i], xy[i + 1], point);
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
            }

            int x = (int) Math.floor(minX);
            int y = (int) Math.floor(minY);
            return new ScreenRectangle(x, y, (int) Math.ceil(maxX) - x, (int) Math.ceil(maxY) - y);
        }
    }

    private static Field state;

    private static void add(GuiGraphicsExtractor out, GuiElementRenderState shape) {
        try {
            if (state == null) {
                Field field = GuiGraphicsExtractor.class.getDeclaredField("guiRenderState");
                field.setAccessible(true);
                state = field;
            }

            ((GuiRenderState) state.get(out)).addGuiElement(shape);
        } catch (ReflectiveOperationException e) {
            // Nothing to draw into; the shape is left out.
        }
    }

    /** Coloured quads drawn where they were recorded. */
    private record Shape(float[] xy, int[] argb, Matrix3x2fc pose, ScreenRectangle bounds)
            implements GuiElementRenderState {
        @Override
        public void buildVertices(VertexConsumer consumer) {
            for (int i = 0; i < argb.length; i++) {
                consumer.addVertexWith2DPose(pose, xy[i * 2], xy[i * 2 + 1]).setColor(argb[i]);
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public ScreenRectangle scissorArea() {
            return null;
        }
    }
}
