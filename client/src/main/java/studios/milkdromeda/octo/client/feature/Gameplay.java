package studios.milkdromeda.octo.client.feature;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import studios.milkdromeda.octo.client.Config;
import studios.milkdromeda.octo.client.OctoClient;

/** The small things Octo does as you play: sprint for you, say where you died. */
public final class Gameplay {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static boolean sprintHeld;
    private static boolean wasDead;

    private Gameplay() {
    }

    /** Every tick. */
    public static void tick(Minecraft minecraft) {
        Config config = OctoClient.config();
        LocalPlayer player = minecraft.player;

        if (player == null) {
            wasDead = false;
            releaseSprint(minecraft);
            return;
        }

        // Toggle sprint: the sprint key held for you while you walk forward;
        // the game still decides whether you may (hunger, sneaking, using an item).
        if (config.toggleSprint && minecraft.gui.screen() == null) {
            boolean forward = minecraft.options.keyUp.isDown();
            minecraft.options.keySprint.setDown(forward);
            sprintHeld = forward;
        } else {
            releaseSprint(minecraft);
        }

        boolean dead = player.isDeadOrDying();

        if (dead && !wasDead && config.deathCoordinates) {
            minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal("You died at ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(player.getBlockX() + ", " + player.getBlockY() + ", "
                            + player.getBlockZ()).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" in " + dimension(player)).withStyle(ChatFormatting.GRAY)));
        }

        wasDead = dead;
    }

    /** Lets go of the sprint key Octo held, once; never a key the player holds. */
    private static void releaseSprint(Minecraft minecraft) {
        if (sprintHeld) {
            minecraft.options.keySprint.setDown(false);
            sprintHeld = false;
        }
    }

    private static String dimension(LocalPlayer player) {
        String path = player.level().dimension().identifier().getPath();
        return switch (path) {
            case "overworld" -> "the Overworld";
            case "the_nether" -> "the Nether";
            case "the_end" -> "the End";
            default -> path.replace('_', ' ');
        };
    }

    /** A chat message with the time it came before it, when timestamps are on. */
    public static Component stamped(Component message) {
        if (message == null || !OctoClient.config().chatTimestamps) {
            return message;
        }

        MutableComponent stamp = Component.literal("[" + LocalTime.now().format(STAMP) + "] ")
                .withStyle(ChatFormatting.DARK_GRAY);
        return stamp.append(message);
    }
}
