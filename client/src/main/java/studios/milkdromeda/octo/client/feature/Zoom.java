package studios.milkdromeda.octo.client.feature;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;

import studios.milkdromeda.octo.client.Config;
import studios.milkdromeda.octo.client.OctoClient;

/** Holding the zoom key brings things closer: the field of view divided, eased in and out. */
public final class Zoom {
    private static float current = 1f;
    private static float extra = 0f;
    private static long lastFrame = System.nanoTime();

    private Zoom() {
    }

    /** Whether the zoom key is held, with nothing on screen to type into. */
    public static boolean held() {
        Config config = OctoClient.config();
        return config.zoom && Minecraft.getInstance().gui.screen() == null && InputConstants.isKeyDown(config.zoomKey);
    }

    /** The field of view to use in place of {@code fov}. */
    public static float apply(float fov) {
        Config config = OctoClient.config();
        boolean holding = held();

        if (!holding) {
            extra = 0f;
        }

        float target = holding ? Math.max(1f, Math.min(50f, config.zoomLevel + extra)) : 1f;
        long now = System.nanoTime();
        float seconds = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;

        if (config.smoothZoom) {
            // Eased: most of the way in a tenth of a second.
            current += (target - current) * (1f - (float) Math.exp(-seconds * 18f));
        } else {
            current = target;
        }

        return current <= 1.001f ? fov : fov / current;
    }

    /** The mouse wheel while zooming: further in or out. True when it was used for that. */
    public static boolean scroll(double amount) {
        if (!held() || !OctoClient.config().scrollToZoom || amount == 0) {
            return false;
        }

        int base = OctoClient.config().zoomLevel;
        extra = Math.max(1 - base, Math.min(40 - base, extra + (float) Math.signum(amount) * Math.max(1, (base + extra) / 4)));
        return true;
    }
}
