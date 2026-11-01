package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.sounds.SoundEngine;

import studios.milkdromeda.octo.client.music.MusicPlayer;

/**
 * Tells Octo's music when the game's sound device is open, and when it is
 * about to close. Nothing of OpenAL may be touched before the game has opened
 * it: the first call made too early leaves OpenAL unusable for the game too.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Shadow
    private boolean loaded;

    @Inject(method = "loadLibrary", at = @At("TAIL"))
    private void octo$opened(CallbackInfo callback) {
        MusicPlayer.get().deviceReady(loaded);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void octo$closing(CallbackInfo callback) {
        MusicPlayer.get().deviceReady(false);
    }
}
