package net.minecraft.client.gui;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Optional;

import org.joml.Matrix3x2f;
import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

/**
 * The {@code GuiGraphics} screens were drawn with until 26.x, for mods built
 * then, drawing through the {@link GuiGraphicsExtractor} the game draws with
 * now.
 *
 * <p>26.1 made drawing a screen a matter of collecting what to draw: a
 * screen's {@code render(GuiGraphics, ...)} became
 * {@code extractRenderState(GuiGraphicsExtractor, ...)}, and the old class
 * went. A mod from before then names it in every screen and widget it has —
 * Create in every menu — and the copy Octo added back from the mod's own
 * release drew through a buffer source and render system that are gone, so
 * nothing it was asked to draw appeared. This one has the old class's
 * members, with the descriptors mods link against, and hands each to the
 * extractor of the frame being drawn
 * ({@link studios.milkdromeda.octo.transform.LegacyScreenBridge} makes one
 * for each call the game makes into a mod's screen).
 *
 * <p>The old pose stack was three-dimensional and the extractor's is flat:
 * what a mod moves, scales or turns about the screen's own axis is drawn
 * where it put it, and depth is left to the order things are drawn in, which
 * is how the game layers its own screens now.
 */
public class GuiGraphics {
    public static final float MAX_GUI_Z = 10000.0F;
    public static final float MIN_GUI_Z = -10000.0F;

    /** The extractor that last drew a screen background, so a frame is blurred once (the game allows no more). */
    private static WeakReference<GuiGraphicsExtractor> backgroundDrawnBy = new WeakReference<>(null);
    private static MultiBufferSource.BufferSource buffers;

    private final Minecraft minecraft;
    /** What this draws into; none for one a mod made itself, which then draws nothing. */
    private final GuiGraphicsExtractor out;
    private final PoseStack pose = new PoseStack();
    /** The tint {@link #setColor} sets, as ARGB. */
    private int tint = -1;

    public GuiGraphics(Minecraft minecraft, MultiBufferSource.BufferSource buffers) {
        this(minecraft, (GuiGraphicsExtractor) null);
    }

    private GuiGraphics(Minecraft minecraft, GuiGraphicsExtractor out) {
        this.minecraft = minecraft;
        this.out = out;
    }

    // ------------------------------------------------------------------ Octo

    /** The extractor of the frame last drawn into through one of these, for what draws without one. */
    private static volatile GuiGraphicsExtractor current;

    /** An old-style view of the frame being drawn. */
    public static GuiGraphics octo$of(GuiGraphicsExtractor out) {
        current = out;
        return new GuiGraphics(Minecraft.getInstance(), out);
    }

    /** The frame being drawn, for shapes a mod draws with the old tesselator. */
    public static GuiGraphicsExtractor octo$current() {
        return current;
    }

    /**
     * The extractor, moved to where this one's pose stack is, for a call into
     * the game's drawing on this one's behalf; {@link #octo$leave()} after.
     */
    public GuiGraphicsExtractor octo$enter() {
        if (out != null) {
            out.pose().pushMatrix();
            out.pose().mul(flat());
        }

        return out;
    }

    public void octo$leave() {
        if (out != null) {
            out.pose().popMatrix();
        }
    }

    /**
     * An item drawn 16 pixels square where an old pose stack puts it, into
     * the frame being drawn: what mods drew through the item renderer's 3D
     * pipeline into a screen or the HUD (catnip's {@code GuiGameElement}, the
     * icon on Create's goggles overlay and in its screens).
     */
    /**
     * A tooltip line's text, as {@code renderText(font, x, y, matrix, buffers)}
     * drew it before 26.x, drawn into the frame being collected where the
     * matrix puts it: catnip's and Create's own tooltips.
     */
    public static void octo$tooltipText(
            net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent component, Font font, int x,
            int y, Matrix4f matrix) {
        GuiGraphicsExtractor out = current;

        if (out == null || component == null) {
            return;
        }

        out.pose().pushMatrix();

        if (matrix != null) {
            out.pose().mul(new Matrix3x2f(matrix.m00(), matrix.m01(), matrix.m10(), matrix.m11(), matrix.m30(),
                    matrix.m31()));
        }

        component.extractText(out, font != null ? font : Minecraft.getInstance().font, x, y);
        out.pose().popMatrix();
    }

    public static void octo$item(PoseStack pose, ItemStack stack) {
        GuiGraphicsExtractor out = current;

        if (out == null || pose == null || stack == null || stack.isEmpty()) {
            return;
        }

        Matrix4f m = pose.last().pose();
        Matrix3x2f at = new Matrix3x2f(m.m00(), m.m01(), m.m10(), m.m11(), m.m30(), m.m31());

        // A pose set up for 3D models has its y turned up (catnip's scale(1, -1, 1));
        // the flat item the game draws reads down, as the model came out on screen.
        if (m.m11() < 0) {
            at.scale(1.0F, -1.0F);
        }

        ItemStack drawn = stack.copy();

        // Raised above a tooltip, as Create's goggles icon is: 26.x layers by
        // order, not depth, so it is drawn once what comes after it is.
        if (m.m32() >= RAISED_Z) {
            DEFERRED.add(new Deferred(out, at, drawn));
            return;
        }

        drawItem(out, at, drawn);
    }

    /** Depth at and above which an old drawing meant to be over tooltips (drawn at 400). */
    private static final float RAISED_Z = 400.0F;
    private static final List<Deferred> DEFERRED = new java.util.ArrayList<>();

    private record Deferred(GuiGraphicsExtractor out, Matrix3x2f at, ItemStack stack) {
    }

    private static void drawItem(GuiGraphicsExtractor out, Matrix3x2f at, ItemStack stack) {
        out.pose().pushMatrix();
        out.pose().mul(at);
        out.item(stack, 0, 0);
        out.pose().popMatrix();
    }

    /** Draws what was raised above the rest, over everything drawn so far into the same frame. */
    public static void octo$flushRaised() {
        if (DEFERRED.isEmpty()) {
            return;
        }

        GuiGraphicsExtractor out = current;
        boolean raised = false;

        for (Deferred deferred : DEFERRED) {
            if (deferred.out() == out) {
                if (!raised) {
                    out.nextStratum();
                    raised = true;
                }

                drawItem(out, deferred.at(), deferred.stack());
            }
        }

        DEFERRED.clear();
    }

    /** Whether this frame's screen background is still to be drawn: the first asking draws it. */
    public boolean octo$firstBackground() {
        if (out == null || backgroundDrawnBy.get() == out) {
            return false;
        }

        backgroundDrawnBy = new WeakReference<>(out);
        return true;
    }

    private Matrix3x2f flat() {
        Matrix4f m = pose.last().pose();
        return new Matrix3x2f(m.m00(), m.m01(), m.m10(), m.m11(), m.m30(), m.m31());
    }

    private boolean begin() {
        if (out == null) {
            return false;
        }

        out.pose().pushMatrix();
        out.pose().mul(flat());
        return true;
    }

    private void end() {
        out.pose().popMatrix();
    }

    /** A colour for text: before 1.21.6 a colour with no alpha was drawn opaque, now it is not drawn. */
    private static int opaque(int color) {
        return (color & 0xFC000000) == 0 ? color | 0xFF000000 : color;
    }

    // ------------------------------------------------------------------ state

    public void drawManaged(Runnable drawing) {
        drawing.run();
    }

    public int guiWidth() {
        return out != null ? out.guiWidth() : minecraft.getWindow().getGuiScaledWidth();
    }

    public int guiHeight() {
        return out != null ? out.guiHeight() : minecraft.getWindow().getGuiScaledHeight();
    }

    public PoseStack pose() {
        return pose;
    }

    public MultiBufferSource.BufferSource bufferSource() {
        if (buffers == null) {
            buffers = MultiBufferSource.immediate(new ByteBufferBuilder(1536));
        }

        return buffers;
    }

    public void flush() {
    }

    public void setColor(float red, float green, float blue, float alpha) {
        tint = channel(alpha) << 24 | channel(red) << 16 | channel(green) << 8 | channel(blue);
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0F)));
    }

    // ---------------------------------------------------------------- scissor

    public void enableScissor(int x1, int y1, int x2, int y2) {
        if (begin()) {
            out.enableScissor(x1, y1, x2, y2);
            end();
        }
    }

    public void disableScissor() {
        if (out != null) {
            out.disableScissor();
        }
    }

    public boolean containsPointInScissor(int x, int y) {
        return out == null || out.containsPointInScissor(x, y);
    }

    // ------------------------------------------------------------------ fills

    public void hLine(int x1, int x2, int y, int color) {
        if (begin()) {
            out.horizontalLine(Math.min(x1, x2), Math.max(x1, x2), y, color);
            end();
        }
    }

    public void hLine(RenderType type, int x1, int x2, int y, int color) {
        hLine(x1, x2, y, color);
    }

    public void vLine(int x, int y1, int y2, int color) {
        if (begin()) {
            out.verticalLine(x, Math.min(y1, y2), Math.max(y1, y2), color);
            end();
        }
    }

    public void vLine(RenderType type, int x, int y1, int y2, int color) {
        vLine(x, y1, y2, color);
    }

    public void fill(int x1, int y1, int x2, int y2, int color) {
        if (begin()) {
            out.fill(x1, y1, x2, y2, color);
            end();
        }
    }

    public void fill(int x1, int y1, int x2, int y2, int z, int color) {
        fill(x1, y1, x2, y2, color);
    }

    public void fill(RenderType type, int x1, int y1, int x2, int y2, int color) {
        fill(x1, y1, x2, y2, color);
    }

    public void fill(RenderType type, int x1, int y1, int x2, int y2, int z, int color) {
        fill(x1, y1, x2, y2, color);
    }

    public void fillGradient(int x1, int y1, int x2, int y2, int from, int to) {
        if (begin()) {
            out.fillGradient(x1, y1, x2, y2, from, to);
            end();
        }
    }

    public void fillGradient(int x1, int y1, int x2, int y2, int z, int from, int to) {
        fillGradient(x1, y1, x2, y2, from, to);
    }

    public void fillGradient(RenderType type, int x1, int y1, int x2, int y2, int from, int to, int z) {
        fillGradient(x1, y1, x2, y2, from, to);
    }

    /** An end portal or a gateway drawn into a screen; there is no pipeline for that here, so nothing. */
    public void fillRenderType(RenderType type, int x1, int y1, int x2, int y2, int z) {
    }

    public void renderOutline(int x, int y, int width, int height, int color) {
        if (begin()) {
            out.outline(x, y, width, height, color);
            end();
        }
    }

    // ------------------------------------------------------------------- text

    public void drawCenteredString(Font font, String text, int x, int y, int color) {
        if (text != null && begin()) {
            out.centeredText(font, text, x, y, opaque(color));
            end();
        }
    }

    public void drawCenteredString(Font font, Component text, int x, int y, int color) {
        if (text != null && begin()) {
            out.centeredText(font, text, x, y, opaque(color));
            end();
        }
    }

    public void drawCenteredString(Font font, FormattedCharSequence text, int x, int y, int color) {
        if (text != null && begin()) {
            out.centeredText(font, text, x, y, opaque(color));
            end();
        }
    }

    public int drawString(Font font, String text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, String text, int x, int y, int color, boolean shadow) {
        if (text == null) {
            return 0;
        }

        if (begin()) {
            out.text(font, text, x, y, opaque(color), shadow);
            end();
        }

        return x + font.width(text) + (shadow ? 1 : 0);
    }

    public int drawString(Font font, FormattedCharSequence text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        if (begin()) {
            out.text(font, text, x, y, opaque(color), shadow);
            end();
        }

        return x + font.width(text) + (shadow ? 1 : 0);
    }

    public int drawString(Font font, Component text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    public int drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
        if (text == null) {
            return 0;
        }

        if (begin()) {
            out.text(font, text, x, y, opaque(color), shadow);
            end();
        }

        return x + font.width(text) + (shadow ? 1 : 0);
    }

    /** NeoForge's, from its patch to the game: text at a position between pixels. */
    public int drawString(Font font, String text, float x, float y, int color, boolean shadow) {
        if (text == null) {
            return 0;
        }

        pose.pushPose();
        pose.translate(x - (int) x, y - (int) y, 0.0F);
        int end = drawString(font, text, (int) x, (int) y, color, shadow);
        pose.popPose();
        return end;
    }

    /** NeoForge's, from its patch to the game: text at a position between pixels. */
    public int drawString(Font font, FormattedCharSequence text, float x, float y, int color, boolean shadow) {
        pose.pushPose();
        pose.translate(x - (int) x, y - (int) y, 0.0F);
        int end = drawString(font, text, (int) x, (int) y, color, shadow);
        pose.popPose();
        return end;
    }

    public void drawWordWrap(Font font, FormattedText text, int x, int y, int width, int color) {
        if (begin()) {
            out.textWithWordWrap(font, text, x, y, width, opaque(color), false);
            end();
        }
    }

    public int drawStringWithBackdrop(Font font, Component text, int x, int y, int width, int color) {
        if (begin()) {
            out.textWithBackdrop(font, text, x, y, width, opaque(color));
            end();
        }

        return x + font.width(text);
    }

    // --------------------------------------------------------------- textures

    public void blit(int x, int y, int z, int width, int height, TextureAtlasSprite sprite) {
        if (begin()) {
            out.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height, tint);
            end();
        }
    }

    public void blit(int x, int y, int z, int width, int height, TextureAtlasSprite sprite, float red, float green,
            float blue, float alpha) {
        if (begin()) {
            out.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height,
                    channel(alpha) << 24 | channel(red) << 16 | channel(green) << 8 | channel(blue));
            end();
        }
    }

    public void blitSprite(Identifier sprite, int x, int y, int width, int height) {
        if (begin()) {
            out.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height, tint);
            end();
        }
    }

    public void blitSprite(Identifier sprite, int x, int y, int z, int width, int height) {
        blitSprite(sprite, x, y, width, height);
    }

    public void blitSprite(Identifier sprite, int textureWidth, int textureHeight, int u, int v, int x, int y,
            int width, int height) {
        if (begin()) {
            out.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, textureWidth, textureHeight, u, v, x, y, width,
                    height, tint);
            end();
        }
    }

    public void blitSprite(Identifier sprite, int textureWidth, int textureHeight, int u, int v, int x, int y, int z,
            int width, int height) {
        blitSprite(sprite, textureWidth, textureHeight, u, v, x, y, width, height);
    }

    /** A region of a 256 by 256 texture. */
    public void blit(Identifier texture, int x, int y, int u, int v, int width, int height) {
        blit(texture, x, y, width, height, (float) u, (float) v, width, height, 256, 256);
    }

    public void blit(Identifier texture, int x, int y, int z, float u, float v, int width, int height,
            int textureWidth, int textureHeight) {
        blit(texture, x, y, width, height, u, v, width, height, textureWidth, textureHeight);
    }

    public void blit(Identifier texture, int x, int y, int width, int height, float u, float v, int regionWidth,
            int regionHeight, int textureWidth, int textureHeight) {
        if (begin()) {
            out.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, regionWidth, regionHeight,
                    textureWidth, textureHeight, tint);
            end();
        }
    }

    public void blit(Identifier texture, int x, int y, float u, float v, int width, int height, int textureWidth,
            int textureHeight) {
        blit(texture, x, y, width, height, u, v, width, height, textureWidth, textureHeight);
    }

    public void blit(Identifier texture, int x1, int x2, int y1, int y2, int z, int regionWidth, int regionHeight,
            float u, float v, int textureWidth, int textureHeight) {
        blit(texture, x1, y1, x2 - x1, y2 - y1, u, v, regionWidth, regionHeight, textureWidth, textureHeight);
    }

    public void innerBlit(Identifier texture, int x1, int x2, int y1, int y2, int z, float minU, float maxU,
            float minV, float maxV) {
        if (begin()) {
            out.blit(texture, x1, y1, x2, y2, minU, maxU, minV, maxV);
            end();
        }
    }

    public void innerBlit(Identifier texture, int x1, int x2, int y1, int y2, int z, float minU, float maxU,
            float minV, float maxV, float red, float green, float blue, float alpha) {
        innerBlit(texture, x1, x2, y1, y2, z, minU, maxU, minV, maxV);
    }

    // ------------------------------------------------------------------ items

    public void renderItem(ItemStack stack, int x, int y) {
        if (begin()) {
            out.item(stack, x, y);
            end();
        }
    }

    public void renderItem(ItemStack stack, int x, int y, int seed) {
        if (begin()) {
            out.item(stack, x, y, seed);
            end();
        }
    }

    public void renderItem(ItemStack stack, int x, int y, int seed, int z) {
        renderItem(stack, x, y, seed);
    }

    public void renderFakeItem(ItemStack stack, int x, int y) {
        if (begin()) {
            out.fakeItem(stack, x, y);
            end();
        }
    }

    public void renderFakeItem(ItemStack stack, int x, int y, int seed) {
        if (begin()) {
            out.fakeItem(stack, x, y, seed);
            end();
        }
    }

    public void renderItem(LivingEntity entity, ItemStack stack, int x, int y, int seed) {
        if (begin()) {
            out.item(entity, stack, x, y, seed);
            end();
        }
    }

    public void renderItemDecorations(Font font, ItemStack stack, int x, int y) {
        if (begin()) {
            out.itemDecorations(font, stack, x, y);
            end();
        }
    }

    public void renderItemDecorations(Font font, ItemStack stack, int x, int y, String count) {
        if (begin()) {
            out.itemDecorations(font, stack, x, y, count);
            end();
        }
    }

    // --------------------------------------------------------------- tooltips

    public void setTooltipForNextFrame(Font font, ItemStack stack, int x, int y) {
        if (out != null) {
            out.setTooltipForNextFrame(font, stack, x, y);
        }
    }

    public void setTooltipForNextFrame(Font font, List<Component> lines, Optional<TooltipComponent> image, int x,
            int y) {
        if (out != null) {
            out.setTooltipForNextFrame(font, lines, image, x, y);
        }
    }

    public void setTooltipForNextFrame(Font font, Component line, int x, int y) {
        if (out != null) {
            out.setTooltipForNextFrame(font, line, x, y);
        }
    }

    public void setComponentTooltipForNextFrame(Font font, List<Component> lines, int x, int y) {
        if (out != null) {
            out.setComponentTooltipForNextFrame(font, lines, x, y);
        }
    }

    public void setTooltipForNextFrame(Font font, List<? extends FormattedCharSequence> lines, int x, int y) {
        if (out != null) {
            out.setTooltipForNextFrame(font, lines, x, y);
        }
    }

    public void setTooltipForNextFrame(Font font, List<FormattedCharSequence> lines,
            ClientTooltipPositioner positioner, int x, int y) {
        if (out != null) {
            out.setTooltipForNextFrame(font, lines, positioner, x, y, false);
        }
    }

    /** The hover text of a chat style; screens show it themselves now, so nothing here. */
    public void renderComponentHoverEffect(Font font, Style style, int x, int y) {
    }
}
