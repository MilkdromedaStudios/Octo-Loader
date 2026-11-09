package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.Camera;

import studios.milkdromeda.octo.client.feature.Freelook;
import studios.milkdromeda.octo.client.feature.Zoom;

/**
 * Zoom: the camera's field of view, narrowed while the zoom key is held. The
 * projection reads the field this calculation fills, once a frame.
 *
 * <p>Freelook: while it is held, the camera faces the way the mouse has
 * turned it rather than the way the player faces.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void octo$zoom(float partialTick, CallbackInfoReturnable<Float> callback) {
        callback.setReturnValue(Zoom.apply(callback.getReturnValueF()));
    }

    @WrapOperation(method = "alignWithEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
    private void octo$freelook(Camera camera, float yaw, float pitch, Operation<Void> original) {
        if (Freelook.active()) {
            original.call(camera, Freelook.yaw(), Freelook.pitch());
        } else {
            original.call(camera, yaw, pitch);
        }
    }
}
