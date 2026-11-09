package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import studios.milkdromeda.octo.client.OctoClient;

/** Low fire: the flames on screen while burning, drawn lower, so they cover less of the view. */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
    @Inject(method = "submitFire", at = @At("HEAD"))
    private static void octo$lowerFire(PoseStack pose, SubmitNodeCollector collector, TextureAtlasSprite sprite,
            CallbackInfo callback) {
        pose.pushPose();

        if (OctoClient.config().lowFire) {
            pose.translate(0.0F, -0.35F, 0.0F);
        }
    }

    @Inject(method = "submitFire", at = @At("RETURN"))
    private static void octo$lowerFireDone(PoseStack pose, SubmitNodeCollector collector, TextureAtlasSprite sprite,
            CallbackInfo callback) {
        pose.popPose();
    }
}
