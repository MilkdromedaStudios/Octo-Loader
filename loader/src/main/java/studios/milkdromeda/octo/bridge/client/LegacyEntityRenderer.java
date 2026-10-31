package studios.milkdromeda.octo.bridge.client;

/**
 * An entity renderer from before 26.x, as {@link LegacyRenderers} calls it:
 * through its old {@code render(entity, yaw, partialTick, poseStack, buffers,
 * light)}. Added to such a renderer by {@code LegacyRendererBridge}.
 */
public interface LegacyEntityRenderer {
    void octo$renderEntity(Object entity, float yaw, float partialTick, Object poseStack, Object buffers, int light);
}
