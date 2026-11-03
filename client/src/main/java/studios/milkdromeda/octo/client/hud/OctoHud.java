package studios.milkdromeda.octo.client.hud;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import studios.milkdromeda.octo.client.Config;
import studios.milkdromeda.octo.client.OctoClient;
import studios.milkdromeda.octo.client.map.WaypointMarkers;
import studios.milkdromeda.octo.client.music.MusicPlayer;
import studios.milkdromeda.octo.client.music.SongInfo;
import studios.milkdromeda.octo.client.ui.Draw;
import studios.milkdromeda.octo.client.ui.Theme;

/**
 * Octo's HUD, drawn after Minecraft's own: the loadout's lines of text in a
 * panel at the top left; the minimap, armour and keystrokes down the right;
 * waypoint markers in the world; and the now-playing card.
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

        if (config.waypointsInWorld) {
            WaypointMarkers.extract(g, minecraft);
        }

        // Octo's own HUD size: drawn scaled, laid out in the scaled space so it still meets the edges.
        float scale = config.hudScale / 100f;
        int width = Math.round(g.guiWidth() / scale);
        int height = Math.round(g.guiHeight() / scale);
        g.pose().pushMatrix();
        g.pose().scale(scale, scale);

        int right = width - 6;
        // Below Minecraft's effect icons, which have the top right corner first.
        int y = 6 + effectRows(player) * 26;
        int top = y;
        // The column down the right: where it reaches, so the now-playing card can keep clear of it.
        int columnLeft = width;

        if (config.minimap) {
            y = Minimap.extract(g, minecraft, right, y) + 6;
            columnLeft = Math.min(columnLeft, Minimap.drawnLeft());
        }

        if (config.hud) {
            Set<HudModule> modules = Loadout.of(config).modules(config);
            textPanel(g, minecraft, font, modules, config.hudPanels);

            if (modules.contains(HudModule.ARMOR)) {
                int below = armor(g, minecraft, font, player, right, y, config.hudPanels) + 6;
                columnLeft = below > y ? Math.min(columnLeft, right - 62) : columnLeft;
                y = below;
            }

            if (modules.contains(HudModule.KEYSTROKES)) {
                y = keystrokes(g, minecraft, font, right, y) + 6;
                columnLeft = Math.min(columnLeft, right - 58);
            }
        }

        if (config.music && config.nowPlaying) {
            nowPlaying(g, minecraft, font, config, width, height, columnLeft, y > top ? y : 0);
        }

        g.pose().popMatrix();
        toast(g, font);
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

    /** @return the y below what it drew */
    private static int keystrokes(GuiGraphicsExtractor g, Minecraft minecraft, Font font, int right, int top) {
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
        return mouseY + size + gap + 12;
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

    private static final Identifier COVER = Identifier.fromNamespaceAndPath("octo-client", "now_playing_cover");
    private static final int CARD_W = 196;
    private static final int CARD_H = 46;
    private static DynamicTexture cover;
    /** The pixels now in the cover texture, to tell when the song's cover changes. */
    private static int[] coverShown;

    /**
     * The now-playing card: the cover, the title, the artist and album, and how
     * far through the song is. When a song starts, or all the time.
     */
    private static void nowPlaying(GuiGraphicsExtractor g, Minecraft minecraft, Font font, Config config, int width,
            int height, int columnLeft, int columnBottom) {
        MusicPlayer music = MusicPlayer.get();
        SongInfo song = music.info();

        if (song == null) {
            return;
        }

        long since = System.currentTimeMillis() - music.startedAt();
        float shown;

        if (config.nowPlayingStyle == 1) {
            shown = Math.min(1f, since / 300f);
        } else if (since > 6000) {
            return;
        } else {
            shown = since < 300 ? since / 300f : since > 5500 ? (6000 - since) / 500f : 1f;
        }

        // Eased: quick to arrive, slow to settle.
        shown = 1 - (1 - shown) * (1 - shown) * (1 - shown);
        float size = config.nowPlayingScale / 100f;
        int w = Math.round(CARD_W * size);
        int h = Math.round(CARD_H * size);
        int x;
        int y;

        if (config.nowPlayingPlace == 1) {
            x = (width - w) / 2;
            y = 6 - Math.round((1 - shown) * (h + 10));
        } else {
            // Minecraft's hotbar (182 wide) with the hearts and food above it, in this scaled space.
            float scale = config.hudScale / 100f;
            int[] at = corner(width, height, w, h, columnLeft, columnBottom,
                    width / 2 + Math.round(95 / scale), height - Math.round(42 / scale));
            x = at[0] + Math.round((1 - shown) * (width - at[0] + 4));
            y = at[1];
        }

        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(size, size);
        card(g, minecraft, font, song, since);
        g.pose().popMatrix();
    }

    /**
     * Where the card goes in the bottom right: in the corner, clear of
     * Minecraft's hotbar, hearts and food (lifted above them when the screen
     * is too narrow to sit beside them), and clear of the column down the
     * right (the minimap, armour, keystrokes): below it if there is room, else
     * at the top in the middle.
     *
     * @param columnBottom the y below the column; 0 when nothing is drawn there
     * @param hotbarRight  the x just right of the hotbar
     * @param hotbarTop    the y above the hotbar, hearts and food
     */
    static int[] corner(int width, int height, int w, int h, int columnLeft, int columnBottom, int hotbarRight,
            int hotbarTop) {
        int x = width - w - 6;
        int y = height - h - 6;

        if (x < hotbarRight) {
            y = Math.min(y, hotbarTop - h - 4);
        }

        if (columnBottom > y - 4 && columnLeft < x + w) {
            int below = columnBottom + 2;

            if (below + h <= (x < hotbarRight ? hotbarTop - 4 : height - 2)) {
                y = below;
            } else {
                x = (width - w) / 2;
                y = 6;
            }
        }

        return new int[] { x, y };
    }

    /** The card itself, at 0, 0, at its own size. */
    private static void card(GuiGraphicsExtractor g, Minecraft minecraft, Font font, SongInfo song, long since) {
        int x = 0;
        int y = 0;

        Draw.shadow(g, x, y, CARD_W, CARD_H, 8);
        Draw.round(g, x, y, CARD_W, CARD_H, 8, Theme.alpha(Theme.WINDOW, 225));
        Draw.outline(g, x, y, CARD_W, CARD_H, 8, Theme.LINE);

        int art = CARD_H - 12;
        int ax = x + 6;
        int ay = y + 6;

        if (song.cover() != null && cover(minecraft, song.cover())) {
            g.blit(COVER, ax, ay, ax + art, ay + art, 0f, 1f, 0f, 1f);
        } else {
            Draw.gradient(g, ax, ay, art, art, 5, Theme.accent(), Theme.accentEnd());
            note(g, ax + art / 2, ay + art / 2);
        }

        int tx = ax + art + 8;
        int room = x + CARD_W - 8 - tx;
        g.text(font, fit(font, song.title(), room), tx, y + 7, Theme.TEXT, false);
        String byline = song.artist() == null ? song.album()
                : song.album() == null ? song.artist() : song.artist() + " · " + song.album();

        if (byline != null) {
            g.text(font, fit(font, byline, room), tx, y + 18, Theme.MUTED, false);
        }

        long elapsed = Math.max(0, since);
        long length = song.millis();
        String time = length > 0 ? clock(Math.min(elapsed, length)) + " / " + clock(length) : clock(elapsed);
        int timeW = font.width(time);
        g.text(font, time, x + CARD_W - 8 - timeW, y + 31, Theme.MUTED, false);

        if (length > 0) {
            int barW = room - timeW - 8;
            int barY = y + 34;
            Draw.round(g, tx, barY, barW, 3, 1, Theme.alpha(Theme.TEXT, 40));
            int filled = Math.round(barW * Math.min(1f, elapsed / (float) length));

            if (filled > 0) {
                Draw.gradient(g, tx, barY, Math.max(2, filled), 3, 1, Theme.accent(), Theme.accentEnd());
            }
        } else {
            equaliser(g, tx, y + 30);
        }
    }

    /** Puts a song's cover in the texture, once per cover; false when there is no texture to draw. */
    private static boolean cover(Minecraft minecraft, int[] pixels) {
        try {
            if (cover == null) {
                cover = new DynamicTexture(() -> "Octo now playing",
                        new NativeImage(SongInfo.COVER, SongInfo.COVER, true));
                minecraft.getTextureManager().register(COVER, cover);
                coverShown = null;
            }

            if (pixels != coverShown) {
                NativeImage image = cover.getPixels();

                if (image == null) {
                    return false;
                }

                for (int i = 0; i < pixels.length; i++) {
                    image.setPixel(i % SongInfo.COVER, i / SongInfo.COVER, pixels[i] | 0xFF000000);
                }

                cover.upload();
                coverShown = pixels;
            }

            return true;
        } catch (RuntimeException e) {
            cover = null;
            return false;
        }
    }

    /** A quaver, white, centred on x, y. */
    private static void note(GuiGraphicsExtractor g, int x, int y) {
        Draw.round(g, x - 6, y + 2, 7, 6, 3, 0xFFFFFFFF);
        g.fill(x, y - 9, x + 2, y + 5, 0xFFFFFFFF);
        g.fill(x + 2, y - 9, x + 6, y - 7, 0xFFFFFFFF);
        g.fill(x + 4, y - 7, x + 6, y - 4, 0xFFFFFFFF);
    }

    /** Three bars that move, for a song whose length is not known. */
    private static void equaliser(GuiGraphicsExtractor g, int x, int y) {
        long t = System.currentTimeMillis();

        for (int i = 0; i < 3; i++) {
            int h = 3 + (int) Math.round(3 * (1 + Math.sin(t / (180.0 + i * 70) + i * 2)));
            g.fill(x + i * 4, y + 9 - h, x + i * 4 + 2, y + 9, Theme.accent());
        }
    }

    /** "m:ss", or "h:mm:ss" for something long. */
    static String clock(long millis) {
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** The text, cut short with an ellipsis to fit {@code width}. */
    static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }

        int end = text.length();

        while (end > 0 && font.width(text.substring(0, end) + "…") > width) {
            end--;
        }

        return text.substring(0, end).stripTrailing() + "…";
    }

    // ------------------------------------------------------------ toast

    private static String toast;
    private static long toastAt;

    /** A short message at the top of the screen, for two and a half seconds. */
    public static void toast(String message) {
        toast = message;
        toastAt = System.currentTimeMillis();
    }

    private static void toast(GuiGraphicsExtractor g, Font font) {
        long since = System.currentTimeMillis() - toastAt;

        if (toast == null || since > 2500) {
            return;
        }

        float fade = since < 200 ? since / 200f : since > 2100 ? (2500 - since) / 400f : 1f;
        int w = font.width(toast) + 22;
        int x = (g.guiWidth() - w) / 2;
        int y = g.guiHeight() - 82 - Math.round((1 - fade) * 6);
        Draw.round(g, x, y, w, 18, 6, Theme.alpha(Theme.WINDOW, Math.round(220 * fade)));
        Draw.round(g, x + 6, y + 6, 6, 6, 3, Theme.alpha(Theme.accent(), Math.round(255 * fade)));
        g.text(font, toast, x + 16, y + 5, Theme.alpha(Theme.TEXT, Math.max(4, Math.round(255 * fade))), false);
    }
}
