package net.minecraft.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * The spinning title panorama as screens drew it until 26.x, drawing the one
 * the game draws now.
 *
 * <p>26.1 gave the panorama to the game renderer; a mod's screen that drew it
 * behind itself (Create's menu does) held a {@code PanoramaRenderer} of its
 * own, and the copy added back from its release drew through a cube map
 * renderer that is gone. This draws the game's.
 */
public class PanoramaRenderer {
    public static final Identifier PANORAMA_OVERLAY = Panorama.PANORAMA_OVERLAY;

    public PanoramaRenderer(CubeMap cubeMap) {
    }

    public void render(GuiGraphics graphics, int width, int height, float fade, float partialTick) {
        GuiGraphicsExtractor out = graphics.octo$enter();

        try {
            if (out != null) {
                Minecraft.getInstance().gameRenderer.panorama().extractRenderState(out, width, height);
            }
        } finally {
            graphics.octo$leave();
        }
    }
}
