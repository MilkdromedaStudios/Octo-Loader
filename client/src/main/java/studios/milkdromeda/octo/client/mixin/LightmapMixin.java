package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;

import studios.milkdromeda.octo.client.OctoClient;

/** Fullbright: the light map as night vision makes it, without the effect, its icon or its flicker. */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapMixin {
    @Inject(method = "extract", at = @At("RETURN"))
    private void octo$fullbright(LightmapRenderState state, float partialTick, CallbackInfo callback) {
        if (OctoClient.config().fullbright) {
            // The shader lifts every light level to at least this colour times the
            // intensity; the colour is only set while the effect is on.
            state.nightVisionColor = LightmapRenderStateExtractor.WHITE;
            state.nightVisionEffectIntensity = 1.0F;
            state.darknessEffectScale = 0.0F;
            state.brightness = Math.max(state.brightness, 1.0F);
            state.needsUpdate = true;
        }
    }
}
