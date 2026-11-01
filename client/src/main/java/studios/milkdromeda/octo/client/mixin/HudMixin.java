package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;

import studios.milkdromeda.octo.client.hud.OctoHud;

/** Octo's HUD, after Minecraft's own, unless the HUD is hidden (F1). */
@Mixin(Hud.class)
public abstract class HudMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void octo$hud(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();

        if (!((Hud) (Object) this).isHidden() && !minecraft.getDebugOverlay().showDebugScreen()) {
            OctoHud.extract(graphics, minecraft);
        }
    }
}
