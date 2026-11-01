package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MouseHandler;

import studios.milkdromeda.octo.client.feature.Zoom;

/** The mouse wheel while zooming adjusts the zoom instead of changing the held item. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void octo$zoomScroll(long window, double scrollX, double scrollY, CallbackInfo callback) {
        if (Zoom.scroll(scrollY)) {
            callback.cancel();
        }
    }
}
