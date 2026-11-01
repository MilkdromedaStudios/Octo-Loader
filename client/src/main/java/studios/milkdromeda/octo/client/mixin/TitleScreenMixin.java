package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import studios.milkdromeda.octo.client.OctoClient;
import studios.milkdromeda.octo.client.ui.Draw;
import studios.milkdromeda.octo.client.ui.OctoMenuScreen;
import studios.milkdromeda.octo.client.ui.Theme;

/** The title screen: the Octo menu's button, and Octo Client's name in the corner. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void octo$button(CallbackInfo callback) {
        Screen self = this;
        addRenderableWidget(Button.builder(Component.literal("Octo Client"),
                button -> minecraft.gui.setScreen(new OctoMenuScreen(self))).bounds(width - 96, 6, 90, 20).build());
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void octo$brand(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
            CallbackInfo callback) {
        String text = "OCTO CLIENT";
        int w = font.width(text) + 14;
        int x = 6;
        int y = 6;
        Draw.round(graphics, x, y, w, 14, 7, Theme.alpha(Theme.WINDOW, 190));
        Draw.round(graphics, x + 4, y + 5, 4, 4, 2, Theme.accent());
        graphics.text(font, text, x + 11, y + 3, Theme.TEXT, false);
        String hint = OctoClient.keyName(OctoClient.config().menuKey) + " for settings";
        graphics.text(font, hint, x + w + 6, y + 3, Theme.alpha(Theme.MUTED, 200), true);
    }
}
