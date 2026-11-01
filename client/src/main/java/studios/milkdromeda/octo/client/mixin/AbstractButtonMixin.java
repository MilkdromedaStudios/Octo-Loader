package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;

import studios.milkdromeda.octo.client.OctoClient;
import studios.milkdromeda.octo.client.ui.WidgetStyle;

/** Buttons in Octo's style: the button's own background, swapped; its label and icon stay the game's. */
@Mixin(AbstractButton.class)
public abstract class AbstractButtonMixin {
    @Inject(method = "extractDefaultSprite", at = @At("HEAD"), cancellable = true)
    private void octo$button(GuiGraphicsExtractor graphics, CallbackInfo callback) {
        if (OctoClient.config().modernWidgets) {
            WidgetStyle.button(graphics, (AbstractWidget) (Object) this);
            callback.cancel();
        }
    }
}
