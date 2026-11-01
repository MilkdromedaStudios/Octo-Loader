package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.sounds.MusicManager;

import studios.milkdromeda.octo.client.music.MusicPlayer;

/** Minecraft's music steps aside while the player's own plays. */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void octo$ownMusic(CallbackInfo callback) {
        if (MusicPlayer.get().active()) {
            ((MusicManager) (Object) this).stopPlaying();
            callback.cancel();
        }
    }
}
