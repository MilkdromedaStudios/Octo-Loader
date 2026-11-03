package studios.milkdromeda.octo.client.map;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

import studios.milkdromeda.octo.client.hud.Minimap;
import studios.milkdromeda.octo.client.ui.Draw;
import studios.milkdromeda.octo.client.ui.Theme;

/**
 * Waypoints in the world: a marker over each one in view, with its name and
 * how far it is, placed where the waypoint is on screen through the camera,
 * zoom included.
 */
public final class WaypointMarkers {
    private WaypointMarkers() {
    }

    /** Where on the GUI a point in the world shows, or {@code null} when it is behind the camera. */
    record OnScreen(float x, float y, double distance) {
    }

    public static void extract(GuiGraphicsExtractor g, Minecraft minecraft) {
        Camera camera = minecraft.gameRenderer.mainCamera();

        if (camera == null || !camera.isInitialized() || minecraft.player == null) {
            return;
        }

        Vec3 eye = camera.position();
        int width = g.guiWidth();
        int height = g.guiHeight();
        List<Waypoints.Waypoint> shown = new ArrayList<>();

        for (Waypoints.Waypoint waypoint : Waypoints.here(minecraft)) {
            if (waypoint.visible) {
                shown.add(waypoint);
            }
        }

        // The furthest first, so nearer markers sit on top.
        shown.sort(Comparator.comparingDouble(
                (Waypoints.Waypoint waypoint) -> waypoint.distanceTo(eye.x, eye.y, eye.z)).reversed());
        Font font = minecraft.font;

        for (Waypoints.Waypoint waypoint : shown) {
            OnScreen at = project(eye, camera.yRot(), camera.xRot(), camera.getFov(), width, height,
                    waypoint.x + 0.5, waypoint.y + 1.2, waypoint.z + 0.5);

            if (at == null || at.distance() < 2.5 || at.x() < -40 || at.x() > width + 40 || at.y() < -20
                    || at.y() > height + 20) {
                continue;
            }

            int x = Math.round(at.x());
            int y = Math.round(at.y());
            Minimap.diamond(g, x, y, 4, waypoint.colour);
            String label = waypoint.name + "  " + distance(at.distance());
            int w = font.width(label) + 10;
            int top = y - 20;
            Draw.round(g, x - w / 2, top, w, 12, 4, Theme.alpha(Theme.WINDOW, 190));
            g.text(font, waypoint.name, x - w / 2 + 5, top + 2, Theme.TEXT, false);
            g.text(font, distance(at.distance()), x - w / 2 + 5 + font.width(waypoint.name + "  "), top + 2,
                    Theme.MUTED, false);
        }
    }

    static String distance(double blocks) {
        return blocks >= 10_000 ? String.format(java.util.Locale.ROOT, "%.1f km", blocks / 1000)
                : Math.round(blocks) + " m";
    }

    /**
     * A point through a camera at {@code eye} looking along yaw and pitch, with
     * a vertical field of view of {@code fov} degrees, onto a screen of the
     * given size.
     */
    static OnScreen project(Vec3 eye, float yaw, float pitch, float fov, int width, int height, double x, double y,
            double z) {
        double dx = x - eye.x;
        double dy = y - eye.y;
        double dz = z - eye.z;
        double yawR = Math.toRadians(yaw);
        double pitchR = Math.toRadians(pitch);
        double sinYaw = Math.sin(yawR);
        double cosYaw = Math.cos(yawR);
        double sinPitch = Math.sin(pitchR);
        double cosPitch = Math.cos(pitchR);

        // Minecraft's axes: yaw 0 looks south (+z), and pitch is positive looking down.
        double fx = -sinYaw * cosPitch;
        double fy = -sinPitch;
        double fz = cosYaw * cosPitch;
        double rx = -cosYaw;
        double rz = -sinYaw;
        // Up is right × forward.
        double ux = -rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy;

        double forward = dx * fx + dy * fy + dz * fz;

        if (forward < 0.05) {
            return null;
        }

        double right = dx * rx + dz * rz;
        double up = dx * ux + dy * uy + dz * uz;
        double focal = (height / 2.0) / Math.tan(Math.toRadians(fov) / 2);
        return new OnScreen((float) (width / 2.0 + right / forward * focal),
                (float) (height / 2.0 - up / forward * focal), Math.sqrt(dx * dx + dy * dy + dz * dz));
    }
}
