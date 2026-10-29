package studios.milkdromeda.octo.bridge.client;

/**
 * A block entity renderer from before 26.x, as {@link LegacyRenderers} calls
 * it: through the method it draws with, under the name the bridge gives it.
 * Added to such a renderer by {@code LegacyRendererBridge}.
 */
public interface LegacyRenderer {
    /**
     * Draws as the renderer did before render states: straight into the
     * buffers it is handed, at the pose it is handed.
     *
     * @param camera where the camera is, for the renderers that took it
     */
    void octo$render(Object blockEntity, float partialTick, Object poseStack, Object buffers, int light, int overlay,
            Object camera);
}
