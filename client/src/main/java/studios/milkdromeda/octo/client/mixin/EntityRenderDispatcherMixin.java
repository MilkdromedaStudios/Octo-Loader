package studios.milkdromeda.octo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import studios.milkdromeda.octo.client.Config;
import studios.milkdromeda.octo.client.OctoClient;

/** Entity distance: mobs and items beyond it are not drawn at all, which is the cheapest drawing there is. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void octo$distance(E entity, Frustum frustum, double cameraX, double cameraY,
            double cameraZ, float partialTick, CallbackInfoReturnable<Boolean> callback) {
        Config config = OctoClient.config();

        if (config.entityDistance <= 0 || config.alwaysShowPlayers && entity instanceof Player) {
            return;
        }

        double limit = config.entityDistance;

        if (entity.distanceToSqr(cameraX, cameraY, cameraZ) > limit * limit) {
            callback.setReturnValue(false);
        }
    }
}
