package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Camera;

import studios.milkdromeda.octo.client.feature.Zoom;

/**
 * Zoom: the camera's field of view, narrowed while the zoom key is held. The
 * projection reads the field this calculation fills, once a frame.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void octo$zoom(float partialTick, CallbackInfoReturnable<Float> callback) {
        callback.setReturnValue(Zoom.apply(callback.getReturnValueF()));
    }
}
