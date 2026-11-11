package studios.milkdromeda.octo.client.feature;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import studios.milkdromeda.octo.client.Config;
import studios.milkdromeda.octo.client.OctoClient;

/**
 * Freelook: while its key is held, the camera swings round the player in
 * third person and the mouse turns the camera, not the player, who goes on
 * facing and walking the way they were. Let go, and the view is as it was.
 */
public final class Freelook {
    private static boolean active;
    private static float yaw;
    private static float pitch;
    private static CameraType before;

    private Freelook() {
    }

    public static boolean active() {
        return active;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    /** Every tick: starts or ends freelook with its key. */
    public static void tick(Minecraft minecraft) {
        Config config = OctoClient.config();
        boolean playing = minecraft.player != null && minecraft.level != null && minecraft.gui.screen() == null;
        boolean held = config.freelook && playing && InputConstants.isKeyDown(config.freelookKey);

        if (held && !active) {
            active = true;
            yaw = minecraft.player.getYRot();
            pitch = minecraft.player.getXRot();
            before = minecraft.options.getCameraType();

            // From behind, whatever the view was: the camera's angles are the mouse's.
            if (before != CameraType.THIRD_PERSON_BACK) {
                minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
        } else if (!held && active) {
            stop(minecraft);
        }
    }

    private static void stop(Minecraft minecraft) {
        active = false;

        if (before != null) {
            minecraft.options.setCameraType(before);
            before = null;
        }
    }

    /**
     * The mouse's turn while freelooking: the camera's, as the player's would
     * be ({@code Entity.turn}'s scale), pitch held between straight up and down.
     */
    public static void turn(double dx, double dy) {
        yaw += (float) dx * 0.15F;
        pitch = Mth.clamp(pitch + (float) dy * 0.15F, -90.0F, 90.0F);
    }
}
