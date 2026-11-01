package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.blaze3d.platform.FramerateLimitTracker;

import net.minecraft.client.Minecraft;

import studios.milkdromeda.octo.client.Config;
import studios.milkdromeda.octo.client.OctoClient;

/**
 * Background frame rate: while the game is not the window in front, it draws
 * at most the frames the player chose, and leaves the computer to what is.
 * Minecraft already slows down when minimised and when idle; this adds the
 * case of another window being in front.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void octo$background(CallbackInfoReturnable<Integer> callback) {
        Config config = OctoClient.config();

        if (config.dynamicFps && !Minecraft.getInstance().isWindowActive()) {
            callback.setReturnValue(Math.min(callback.getReturnValueI(), config.unfocusedFps));
        }
    }
}
