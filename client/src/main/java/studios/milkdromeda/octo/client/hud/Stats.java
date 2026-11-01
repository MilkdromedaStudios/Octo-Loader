package studios.milkdromeda.octo.client.hud;

import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** What the HUD measures over time: speed, from tick to tick, and clicks per second. */
public final class Stats {
    private static double lastX;
    private static double lastZ;
    private static boolean hasLast;
    private static double speed;
    private static final Deque<Long> LEFT = new ArrayDeque<>();
    private static final Deque<Long> RIGHT = new ArrayDeque<>();
    private static boolean leftWasDown;
    private static boolean rightWasDown;

    private Stats() {
    }

    public static void tick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;

        if (player == null) {
            hasLast = false;
            return;
        }

        if (hasLast) {
            double dx = player.getX() - lastX;
            double dz = player.getZ() - lastZ;
            // Blocks a tick, smoothed, to blocks a second.
            speed = speed * 0.6 + Math.sqrt(dx * dx + dz * dz) * 20 * 0.4;
        }

        lastX = player.getX();
        lastZ = player.getZ();
        hasLast = true;
    }

    /** Every frame: counts presses of the mouse buttons as they happen. */
    public static void frame(Minecraft minecraft) {
        long now = System.currentTimeMillis();
        boolean left = minecraft.mouseHandler.isLeftPressed();
        boolean right = minecraft.mouseHandler.isRightPressed();

        if (left && !leftWasDown) {
            LEFT.addLast(now);
        }

        if (right && !rightWasDown) {
            RIGHT.addLast(now);
        }

        leftWasDown = left;
        rightWasDown = right;

        while (!LEFT.isEmpty() && now - LEFT.peekFirst() > 1000) {
            LEFT.removeFirst();
        }

        while (!RIGHT.isEmpty() && now - RIGHT.peekFirst() > 1000) {
            RIGHT.removeFirst();
        }
    }

    public static double speed() {
        return speed;
    }

    public static int leftCps() {
        return LEFT.size();
    }

    public static int rightCps() {
        return RIGHT.size();
    }
}
