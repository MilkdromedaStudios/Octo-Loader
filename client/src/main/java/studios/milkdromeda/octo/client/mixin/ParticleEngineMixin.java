package studios.milkdromeda.octo.client.mixin;

import java.util.concurrent.ThreadLocalRandom;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;

import studios.milkdromeda.octo.client.OctoClient;

/** Particles: only the share the player chose is made, so busy scenes have less to tick and draw. */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void octo$fewer(Particle particle, CallbackInfo callback) {
        int share = OctoClient.config().particles;

        if (share < 100 && ThreadLocalRandom.current().nextInt(100) >= share) {
            callback.cancel();
        }
    }
}
