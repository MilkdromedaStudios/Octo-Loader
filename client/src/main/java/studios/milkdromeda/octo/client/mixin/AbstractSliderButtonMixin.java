package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.resources.Identifier;

import studios.milkdromeda.octo.client.OctoClient;
import studios.milkdromeda.octo.client.ui.WidgetStyle;

/** Sliders in Octo's style: a track filled to the value, and a round handle. */
@Mixin(AbstractSliderButton.class)
public abstract class AbstractSliderButtonMixin {
    @Shadow
    protected double value;

    private static final String BLIT = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V";

    @Redirect(method = "extractWidgetRenderState", at = @At(value = "INVOKE", target = BLIT, ordinal = 0))
    private void octo$track(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y,
            int width, int height, int colour) {
        if (OctoClient.config().modernWidgets) {
            WidgetStyle.sliderTrack(graphics, (AbstractWidget) (Object) this, value);
        } else {
            graphics.blitSprite(pipeline, sprite, x, y, width, height, colour);
        }
    }

    @Redirect(method = "extractWidgetRenderState", at = @At(value = "INVOKE", target = BLIT, ordinal = 1))
    private void octo$handle(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y,
            int width, int height, int colour) {
        if (OctoClient.config().modernWidgets) {
            WidgetStyle.sliderHandle(graphics, (AbstractWidget) (Object) this, x, width);
        } else {
            graphics.blitSprite(pipeline, sprite, x, y, width, height, colour);
        }
    }
}
