package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;

import studios.milkdromeda.octo.client.feature.Freelook;
import studios.milkdromeda.octo.client.feature.Zoom;

/**
 * The mouse wheel while zooming adjusts the zoom instead of changing the held
 * item; while freelooking, the mouse turns the camera instead of the player.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void octo$zoomScroll(long window, double scrollX, double scrollY, CallbackInfo callback) {
        if (Zoom.scroll(scrollY)) {
            callback.cancel();
        }
    }

    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void octo$freelookTurn(LocalPlayer player, double dx, double dy, Operation<Void> original) {
        if (Freelook.active()) {
            Freelook.turn(dx, dy);
        } else {
            original.call(player, dx, dy);
        }
    }
}
