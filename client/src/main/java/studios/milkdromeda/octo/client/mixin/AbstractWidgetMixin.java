package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvents;

import studios.milkdromeda.octo.client.OctoClient;

/** Soft clicks: the same click, lighter and quieter. */
@Mixin(AbstractWidget.class)
public abstract class AbstractWidgetMixin {
    @Inject(method = "playButtonClickSound", at = @At("HEAD"), cancellable = true)
    private static void octo$softClick(SoundManager sounds, CallbackInfo callback) {
        if (OctoClient.config().softClicks) {
            sounds.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.25f, 0.4f));
            callback.cancel();
        }
    }
}
