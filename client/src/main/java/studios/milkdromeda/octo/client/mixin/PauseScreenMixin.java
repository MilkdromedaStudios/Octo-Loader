package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import studios.milkdromeda.octo.client.ui.OctoMenuScreen;

/** The Octo menu, from the pause screen: top left, where no toast covers it. */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void octo$button(CallbackInfo callback) {
        Screen self = this;
        addRenderableWidget(Button.builder(Component.literal("Octo Client"),
                button -> minecraft.gui.setScreen(new OctoMenuScreen(self))).bounds(6, 6, 90, 20).build());
    }
}
