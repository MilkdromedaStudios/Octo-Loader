package studios.milkdromeda.octo.client.hud;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import studios.milkdromeda.octo.client.Config;
import studios.milkdromeda.octo.client.OctoClient;
import studios.milkdromeda.octo.client.music.MusicPlayer;
import studios.milkdromeda.octo.client.ui.Draw;
import studios.milkdromeda.octo.client.ui.Theme;

/**
 * Octo's HUD, drawn after Minecraft's own: the loadout's lines of text in a
 * panel at the top left; the minimap, armour and keystrokes down the right;
 * and the song that just started, at the top.
 */
public final class OctoHud {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final String[] COMPASS = { "South", "South-west", "West", "North-west", "North", "North-east",
            "East", "South-east" };
    private static final EquipmentSlot[] ARMOR = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND };

    private OctoHud() {
    }

    public static void extract(GuiGraphicsExtractor g, Minecraft minecraft) {
        Config config = OctoClient.config();
        LocalPlayer player = minecraft.player;
        Stats.frame(minecraft);

        if (player == null) {
            return;
        }

        Font font = minecraft.font;
        int right = g.guiWidth() - 6;
        // Below Minecraft's effect icons, which have the top right corner first.
        int y = 6 + effectRows(player) * 26;

        if (config.minimap) {
            y = Minimap.extract(g, minecraft, right, y) + 6;
        }

        if (config.hud) {
            Set<HudModule> modules = Loadout.of(config).modules(config);
            textPanel(g, minecraft, font, modules, config.hudPanels);

            if (modules.contains(HudModule.ARMOR)) {
                y = armor(g, minecraft, font, player, right, y, config.hudPanels) + 6;
            }

            if (modules.contains(HudModule.KEYSTROKES)) {
                keystrokes(g, minecraft, font, right, y);
            }
        }

        if (config.music && config.nowPlaying) {
            nowPlaying(g, font);
        }
    }

    /** How many rows of effect icons Minecraft draws at the top right: good ones, then bad ones. */
    private static int effectRows(LocalPlayer player) {
        boolean good = false;
        boolean bad = false;

        for (var effect : player.getActiveEffects()) {
            if (effect.showIcon()) {
                if (effect.getEffect().value().isBeneficial()) {
                    good = true;
                } else {
                    bad = true;
                }
            }
        }

        return (good ? 1 : 0) + (bad ? 1 : 0);
    }

    // ------------------------------------------------------------ text

    private static void textPanel(GuiGraphicsExtractor g, Minecraft minecraft, Font font, Set<HudModule> modules,
            boolean panels) {
        List<String[]> lines = new ArrayList<>();

        for (HudModule module : modules) {
            if (module.isText()) {
                String value = value(minecraft, module);

                if (value != null) {
                    lines.add(new String[] { module.title, value });
                }
            }
        }

        if (lines.isEmpty()) {
            return;
        }

        int labelWidth = 0;
        int valueWidth = 0;

        for (String[] line : lines) {
            labelWidth = Math.max(labelWidth, font.width(line[0]));
            valueWidth = Math.max(valueWidth, font.width(line[1]));
        }

        int x = 6;
        int y = 6;
        int w = labelWidth + valueWidth + 22;
        int h = lines.size() * 11 + 9;

        if (panels) {
            Draw.round(g, x, y, w, h, 6, Theme.alpha(Theme.WINDOW, 170));
            Draw.round(g, x, y + 5, 2, h - 10, 1, Theme.accent());
        }

        for (int i = 0; i < lines.size(); i++) {
            int ly = y + 6 + i * 11;
            g.text(font, lines.get(i)[0], x + 9, ly, Theme.MUTED, !panels);
            g.text(font, lines.get(i)[1], x + 13 + labelWidth, ly, Theme.TEXT, !panels);
        }
    }

    /** A module's value now, or {@code null} when it has none here (ping in single player). */
    static String value(Minecraft minecraft, HudModule module) {
        LocalPlayer player = minecraft.player;
        BlockPos pos = player.blockPosition();

        return switch (module) {
            case FPS -> minecraft.getFps() + "";
            case COORDINATES -> pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
            case DIRECTION -> compass(player.getYRot());
            case BIOME -> player.level().getBiome(pos).unwrapKey()
                    .map(key -> pretty(key.identifier().getPath())).orElse("Unknown");
            case CLOCK -> LocalTime.now().format(CLOCK);
            case SPEED -> String.format(Locale.ROOT, "%.1f m/s", Stats.speed());
            case PING -> {
                PlayerInfo info = minecraft.getConnection() == null ? null
                        : minecraft.getConnection().getPlayerInfo(player.getUUID());
                yield info == null || minecraft.isLocalServer() ? null : info.getLatency() + " ms";
            }
            case CPS -> Stats.leftCps() + " | " + Stats.rightCps();
            case MEMORY -> {
                Runtime runtime = Runtime.getRuntime();
                long used = (runtime.totalMemory() - runtime.freeMemory()) >> 20;
                yield used + " / " + (runtime.maxMemory() >> 20) + " MB";
            }
            default -> null;
        };
    }

    static String compass(float yaw) {
        int index = Math.floorMod(Math.round(yaw / 45f), 8);
        return COMPASS[index];
    }

    /** {@code dark_forest} to "Dark Forest". */
    static String pretty(String id) {
        StringBuilder out = new StringBuilder();

        for (String word : id.split("_")) {
            if (!word.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1));
            }
        }

        return out.toString();
    }

    // ----------------------------------------------------------- armour

    private static int armor(GuiGraphicsExtractor g, Minecraft minecraft, Font font, LocalPlayer player, int right,
            int top, boolean panels) {
        List<ItemStack> worn = new ArrayList<>();

        for (EquipmentSlot slot : ARMOR) {
            ItemStack stack = player.getItemBySlot(slot);

            if (!stack.isEmpty()) {
                worn.add(stack);
            }
        }

        if (worn.isEmpty()) {
            return top - 6;
        }

        int w = 62;
        int h = worn.size() * 18 + 4;
        int x = right - w;

        if (panels) {
            Draw.round(g, x, top, w, h, 6, Theme.alpha(Theme.WINDOW, 170));
        }

        for (int i = 0; i < worn.size(); i++) {
            ItemStack stack = worn.get(i);
            int iy = top + 2 + i * 18;
            g.item(stack, x + 4, iy + 1);
            String text;
            int colour = Theme.TEXT;

            if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
                int left = stack.getMaxDamage() - stack.getDamageValue();
                float share = left / (float) stack.getMaxDamage();
                text = Math.round(share * 100) + "%";
                colour = share > 0.5f ? Theme.GREEN : share > 0.2f ? Theme.AMBER : Theme.RED;
            } else {
                text = "x" + stack.getCount();
            }

            g.text(font, text, x + 24, iy + 5, colour, !panels);
        }

        return top + h;
    }

    // ------------------------------------------------------- keystrokes

    private static void keystrokes(GuiGraphicsExtractor g, Minecraft minecraft, Font font, int right, int top) {
        var options = minecraft.options;
        int size = 18;
        int gap = 2;
        int width = size * 3 + gap * 2;
        int x = right - width;
        key(g, font, options.keyUp, "W", x + size + gap, top, size, size);
        key(g, font, options.keyLeft, "A", x, top + size + gap, size, size);
        key(g, font, options.keyDown, "S", x + size + gap, top + size + gap, size, size);
        key(g, font, options.keyRight, "D", x + (size + gap) * 2, top + size + gap, size, size);
        int mouseY = top + (size + gap) * 2;
        int half = (width - gap) / 2;
        key(g, font, options.keyAttack, "LMB", x, mouseY, half, size);
        key(g, font, options.keyUse, "RMB", x + half + gap, mouseY, width - half - gap, size);
        key(g, font, options.keyJump, "Space", x, mouseY + size + gap, width, 12);
    }

    private static void key(GuiGraphicsExtractor g, Font font, KeyMapping mapping, String label, int x, int y, int w,
            int h) {
        boolean down = mapping.isDown();

        if (down) {
            Draw.gradient(g, x, y, w, h, 4, Theme.accent(), Theme.accentEnd());
        } else {
            Draw.round(g, x, y, w, h, 4, Theme.alpha(Theme.WINDOW, 170));
        }

        g.centeredText(font, label, x + w / 2, y + (h - 8) / 2, down ? 0xFFFFFFFF : Theme.MUTED);
    }

    // ----------------------------------------------------- now playing

    private static void nowPlaying(GuiGraphicsExtractor g, Font font) {
        MusicPlayer music = MusicPlayer.get();
        String song = music.nowPlaying();
        long since = System.currentTimeMillis() - music.startedAt();

        if (song == null || since > 5000) {
            return;
        }

        float fade = since < 300 ? since / 300f : since > 4500 ? (5000 - since) / 500f : 1f;
        String text = "Now playing  " + song;
        int w = font.width(text) + 20;
        int x = (g.guiWidth() - w) / 2;
        int y = 6 - Math.round((1 - fade) * 10);
        Draw.round(g, x, y, w, 18, 6, Theme.alpha(Theme.WINDOW, Math.round(210 * fade)));
        Draw.round(g, x + 6, y + 6, 6, 6, 3, Theme.alpha(Theme.accent(), Math.round(255 * fade)));
        g.text(font, text, x + 16, y + 5, Theme.alpha(Theme.TEXT, Math.max(4, Math.round(255 * fade))), false);
    }
}
