package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;

import studios.milkdromeda.octo.client.OctoClient;

/** Octo's tick: keys, the minimap, the music, after the game's own. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void octo$tick(CallbackInfo callback) {
        OctoClient.tick((Minecraft) (Object) this);
    }
}
