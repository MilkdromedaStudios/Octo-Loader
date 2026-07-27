package studios.milkdromeda.octo.mixin.game;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import studios.milkdromeda.octo.hook.GameHooks;

/**
 * The loader's own mixin: it tells Octo when the client exists.
 *
 * <p>Named by string rather than by class, because the loader is compiled
 * without Minecraft on the class path — and because the name has to be allowed
 * to be wrong. A target that is not there is not an error here; the launcher
 * notices the hook never fired and initialises the mods itself.
 */
@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public class MinecraftStartupMixin {
    @Inject(method = "<init>", at = @At("TAIL"), require = 0, expect = 0)
    private void octo$clientStarted(CallbackInfo callback) {
        GameHooks.clientStarted(this);
    }
}
