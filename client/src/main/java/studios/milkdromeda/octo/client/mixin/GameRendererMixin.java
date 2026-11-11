package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import studios.milkdromeda.octo.client.OctoClient;

/** No hurt shake: the view stays still when you are hurt. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void octo$noHurtShake(CameraRenderState camera, PoseStack pose, CallbackInfo callback) {
        if (OctoClient.config().noHurtShake) {
            callback.cancel();
        }
    }
}
