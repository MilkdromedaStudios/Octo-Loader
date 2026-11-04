package studios.milkdromeda.octo.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import studios.milkdromeda.octo.client.Config;
import studios.milkdromeda.octo.client.OctoClient;
import studios.milkdromeda.octo.client.hud.HudModule;
import studios.milkdromeda.octo.client.hud.Loadout;
import studios.milkdromeda.octo.client.music.MusicPlayer;

/**
 * The Octo menu: every feature of the client, by category, each with a plain
 * switch, choice or slider. A settings screen in Octo Launcher's colours, not
 * a board of panels to drag about.
 */
public final class OctoMenuScreen extends Screen {
    private static final String[] CATEGORIES = { "Look", "HUD", "Zoom", "Minimap", "Music", "Performance" };
    private static final String[] SUBTITLES = {
            "How the game's menus look and sound.",
            "What the heads-up display shows while you play.",
            "Hold a key to look closer.",
            "A map of what is around you, in the corner.",
            "Your own music in place of Minecraft's.",
            "Frame-rate savers. Each one is safe to switch on or off any time."
    };
    private static final int SIDEBAR = 128;
    private static final int ROW = 34;
    private static final int GAP = 6;

    /** One setting in the list. */
    private sealed interface Row permits Toggle, Choice, Slider, Action, KeyRow {
        String title();

        String hint();
    }

    private record Toggle(String title, String hint, BooleanSupplier get, Consumer<Boolean> set) implements Row {
    }

    private record Choice(String title, String hint, List<String> options, IntSupplier get, IntConsumer set)
            implements Row {
    }

    private record Slider(String title, String hint, int min, int max, int step, IntSupplier get, IntConsumer set,
            IntFunction<String> format) implements Row {
    }

    private record Action(String title, Supplier<String> hintSource, String button, Runnable run) implements Row {
        @Override
        public String hint() {
            return hintSource.get();
        }
    }

    private record KeyRow(String title, String hint, IntSupplier get, IntConsumer set) implements Row {
    }

    private final Screen parent;
    private int category;
    private double scroll;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private KeyRow capturing;
    private Slider dragging;
    private long opened;

    public OctoMenuScreen(Screen parent) {
        super(Component.literal("Octo Client"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Theme.follow();
        panelW = Math.min(600, width - 24);
        panelH = Math.min(340, height - 24);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        opened = System.currentTimeMillis();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        OctoClient.config().save();
        minecraft.gui.setScreen(parent);
    }

    private Config config() {
        return OctoClient.config();
    }

    // ------------------------------------------------------------ rows

    private List<Row> rows() {
        Config c = config();
        List<Row> rows = new ArrayList<>();

        switch (category) {
            case 0 -> {
                rows.add(new Toggle("Octo look", "Buttons and sliders in Octo's style, in the "
                        + Character.toUpperCase(Theme.look().charAt(0)) + Theme.look().substring(1)
                        + " theme chosen in Octo Launcher.", () -> c.modernWidgets, v -> c.modernWidgets = v));
                // The accent is the player's on the Octo theme; Dark and Light bring their own.
                if (Theme.look().equals("octo")) {
                    rows.add(new Choice("Accent colour", "The colour of switches, sliders and highlights.",
                            List.of(Theme.ACCENT_NAMES), () -> c.accent, v -> c.accent = v));
                }
                rows.add(new Toggle("Soft clicks", "Gentler, lighter button sounds.", () -> c.softClicks,
                        v -> c.softClicks = v));
                rows.add(new KeyRow("Menu key", "Opens this menu from anywhere in the game.", () -> c.menuKey,
                        v -> c.menuKey = v));
            }
            case 1 -> {
                rows.add(new Toggle("Show the HUD", "Octo's HUD over the game, beside Minecraft's own.",
                        () -> c.hud, v -> c.hud = v));
                rows.add(new Slider("HUD size", "Everything Octo draws on screen: the panel, minimap, keys and "
                        + "card.", 50, 200, 10, () -> c.hudScale, v -> c.hudScale = v, v -> v + "%"));
                Loadout current = Loadout.of(c);
                rows.add(new Choice("Loadout", current.description, Loadout.titles(), current::ordinal,
                        v -> c.loadout = Loadout.values()[v].name()));
                rows.add(new Toggle("Panels", "Soft panels behind the HUD, so it reads over anything.",
                        () -> c.hudPanels, v -> c.hudPanels = v));

                if (current == Loadout.CUSTOM) {
                    for (HudModule module : HudModule.values()) {
                        rows.add(new Toggle(module.title, module.description,
                                () -> c.customModules.contains(module.name()), v -> {
                                    c.customModules.remove(module.name());

                                    if (v) {
                                        c.customModules.add(module.name());
                                    }
                                }));
                    }
                }
            }
            case 2 -> {
                rows.add(new Toggle("Zoom", "Hold the zoom key to look closer.", () -> c.zoom, v -> c.zoom = v));
                rows.add(new KeyRow("Zoom key", "The key you hold.", () -> c.zoomKey, v -> c.zoomKey = v));
                rows.add(new Slider("How close", "How many times closer the zoom brings things.", 2, 12, 1,
                        () -> c.zoomLevel, v -> c.zoomLevel = v, v -> v + "x"));
                rows.add(new Toggle("Smooth zoom", "Glides in and out instead of jumping.", () -> c.smoothZoom,
                        v -> c.smoothZoom = v));
                rows.add(new Toggle("Scroll to zoom", "While zooming, the mouse wheel goes further in and out.",
                        () -> c.scrollToZoom, v -> c.scrollToZoom = v));
            }
            case 3 -> {
                rows.add(new Toggle("Minimap", "A map of the ground around you, north up.", () -> c.minimap,
                        v -> c.minimap = v));
                rows.add(new Slider("Size", "How big the map is on screen.", 64, 256, 8, () -> c.minimapPixels,
                        v -> c.minimapPixels = v, v -> v + " px"));
                rows.add(new Choice("Range", "How much ground it covers.", List.of("Close", "Wide", "Far"),
                        () -> c.minimapScale, v -> c.minimapScale = v));
                rows.add(new Toggle("Coordinates", "Your coordinates under the map.", () -> c.minimapCoordinates,
                        v -> c.minimapCoordinates = v));
                rows.add(new Choice("Layer", "Auto shows caves when you are underground and the surface, "
                        + "shaded by height, above it.", List.of("Auto", "Surface", "Caves"), () -> c.minimapLayer,
                        v -> c.minimapLayer = v));
                rows.add(new KeyRow("Big map key", "Opens the map full screen, with your waypoints.",
                        () -> c.mapKey, v -> c.mapKey = v));
                rows.add(new Action("Big map", () -> "Drag, zoom and set waypoints on it.", "Open", () -> {
                    if (minecraft.player != null) {
                        minecraft.gui.setScreen(new studios.milkdromeda.octo.client.map.BigMapScreen());
                    }
                }));
                rows.add(new KeyRow("Waypoint key", "Sets a waypoint where you stand.", () -> c.waypointKey,
                        v -> c.waypointKey = v));
                rows.add(new Toggle("Waypoints on the map", "Each as a diamond; at the edge when further away.",
                        () -> c.waypointsOnMap, v -> c.waypointsOnMap = v));
                rows.add(new Toggle("Waypoints in the world", "A marker over each, with how far it is.",
                        () -> c.waypointsInWorld, v -> c.waypointsInWorld = v));
                rows.add(new Toggle("Death waypoint", "Marks where you last died.", () -> c.deathWaypoint,
                        v -> c.deathWaypoint = v));
            }
            case 4 -> {
                MusicPlayer music = MusicPlayer.get();
                rows.add(new Toggle("Your music", "Plays the MP3, OGG, FLAC and WAV files in your music "
                        + "folder instead of Minecraft's music.", () -> c.music, v -> c.music = v));
                rows.add(new Slider("Volume", "Also follows the game's master volume.", 0, 100, 5,
                        () -> c.musicVolume, v -> c.musicVolume = v, v -> v + "%"));
                rows.add(new Toggle("Shuffle", "On: songs in a random order. Off: in order by file name.",
                        () -> c.shuffle, v -> c.shuffle = v));
                rows.add(new Toggle("Now playing card", "The song's cover, title, artist and how far through it "
                        + "is.", () -> c.nowPlaying, v -> c.nowPlaying = v));
                rows.add(new Choice("Show the card", "Covers come from the song's tags, or a cover.jpg or "
                        + "cover.png beside it.", List.of("On start", "Always"),
                        () -> c.nowPlayingStyle, v -> c.nowPlayingStyle = v));
                rows.add(new Choice("Card position", "The bottom right corner, or the top middle.",
                        List.of("Corner", "Top"), () -> c.nowPlayingPlace, v -> c.nowPlayingPlace = v));
                rows.add(new Slider("Card size", "How big the now-playing card is.", 60, 200, 10,
                        () -> c.nowPlayingScale, v -> c.nowPlayingScale = v, v -> v + "%"));
                rows.add(new Action("Now playing", () -> {
                    var song = music.info();
                    return song == null
                            ? music.songCount() + (music.songCount() == 1 ? " song" : " songs") + " in your folder"
                            : song.artist() == null ? song.title() : song.title() + " · " + song.artist();
                }, "Next", music::next));
                rows.add(new Action("Music folder", () -> MusicPlayer.folder(minecraft).toString(), "Open",
                        () -> MusicPlayer.openFolder(minecraft)));
            }
            default -> {
                rows.add(new Toggle("Save power in the background", "A lower frame rate while the game is not "
                        + "the window in front.", () -> c.dynamicFps, v -> c.dynamicFps = v));
                rows.add(new Slider("Background frame rate", "Frames a second while in the background.", 5, 60, 5,
                        () -> c.unfocusedFps, v -> c.unfocusedFps = v, v -> v + " FPS"));
                rows.add(new Slider("Entity distance", "Mobs and items further away are not drawn.", 0, 256, 16,
                        () -> c.entityDistance, v -> c.entityDistance = v, v -> v == 0 ? "All" : v + " blocks"));
                rows.add(new Toggle("Always show players", "Players are drawn at any distance.",
                        () -> c.alwaysShowPlayers, v -> c.alwaysShowPlayers = v));
                rows.add(new Slider("Particles", "The share of particles made: fewer is faster in busy scenes.",
                        0, 100, 25, () -> c.particles, v -> c.particles = v, v -> v + "%"));
            }
        }

        return rows;
    }

    // ---------------------------------------------------------- layout

    private int contentX() {
        return panelX + SIDEBAR + 16;
    }

    private int contentW() {
        return panelW - SIDEBAR - 32;
    }

    private int listTop() {
        return panelY + 46;
    }

    private int listBottom() {
        return panelY + panelH - 12;
    }

    private int rowY(int index) {
        return listTop() + index * (ROW + GAP) - (int) scroll;
    }

    private int maxScroll(int rows) {
        return Math.max(0, rows * (ROW + GAP) - GAP - (listBottom() - listTop()));
    }

    /** Where a row's control sits: its right edge, centred on the row. */
    private int controlRight() {
        return contentX() + contentW() - 10;
    }

    /** Each category's height: 24, less when the window is short, so Done always fits under them. */
    private int categoryHeight() {
        return Math.max(16, Math.min(24, (panelH - 46 - 44) / CATEGORIES.length));
    }

    private int categoryY(int index) {
        return panelY + 46 + index * categoryHeight();
    }

    private static boolean inside(double x, double y, int left, int top, int w, int h) {
        return x >= left && x < left + w && y >= top && y < top + h;
    }

    // ----------------------------------------------------------- render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        float appear = Math.min(1f, (System.currentTimeMillis() - opened) / 160f);
        int lift = Math.round((1 - appear) * 8);
        g.fill(0, 0, width, height, Theme.alpha(0xFF04050A, Math.round(110 * appear)));
        g.pose().pushMatrix();
        g.pose().translate(0, lift);

        Draw.shadow(g, panelX, panelY, panelW, panelH, 10);
        Draw.round(g, panelX, panelY, panelW, panelH, 10, Theme.alpha(Theme.WINDOW, 245));
        Draw.outline(g, panelX, panelY, panelW, panelH, 10, Theme.LINE);
        sidebar(g, mouseX, mouseY - lift);
        content(g, mouseX, mouseY - lift);
        g.pose().popMatrix();
    }

    private void sidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Draw.round(g, panelX + 1, panelY + 1, SIDEBAR, panelH - 2, 9, Theme.RAIL);
        g.fill(panelX + SIDEBAR, panelY + 1, panelX + SIDEBAR + 1, panelY + panelH - 1, Theme.LINE);

        // The mark: an accent tile with an O.
        int mx = panelX + 14;
        int my = panelY + 14;
        Draw.gradient(g, mx, my, 16, 16, 4, Theme.accent(), Theme.accentEnd());
        g.text(font, "O", mx + 5, my + 4, 0xFFFFFFFF, false);
        g.text(font, "Octo", mx + 22, my + 1, Theme.TEXT, false);
        g.text(font, "Client", mx + 22 + font.width("Octo "), my + 1, Theme.MUTED, false);
        g.text(font, "Settings", mx + 22, my + 11, Theme.FAINT, false);

        int itemH = categoryHeight() - 4;

        for (int i = 0; i < CATEGORIES.length; i++) {
            int y = categoryY(i);
            boolean selected = i == category;
            boolean hover = inside(mouseX, mouseY, panelX + 8, y, SIDEBAR - 16, itemH);

            if (selected) {
                Draw.round(g, panelX + 8, y, SIDEBAR - 16, itemH, 6, Theme.RAISED);
                Draw.round(g, panelX + 8, y + itemH / 2 - 5, 2, 10, 1, Theme.accent());
            } else if (hover) {
                Draw.round(g, panelX + 8, y, SIDEBAR - 16, itemH, 6, Theme.alpha(Theme.HOVER, 160));
            }

            g.text(font, CATEGORIES[i], panelX + 18, y + (itemH - 8) / 2, selected ? Theme.TEXT : Theme.MUTED,
                    false);
        }

        // Done, at the foot.
        int doneY = panelY + panelH - 32;
        boolean hover = inside(mouseX, mouseY, panelX + 8, doneY, SIDEBAR - 16, 20);
        Draw.gradient(g, panelX + 8, doneY, SIDEBAR - 16, 20, 6, hover ? Theme.mix(Theme.accent(), 0xFFFFFFFF, 0.12f)
                : Theme.accent(), hover ? Theme.mix(Theme.accentEnd(), 0xFFFFFFFF, 0.12f) : Theme.accentEnd());
        g.centeredText(font, "Done", panelX + SIDEBAR / 2, doneY + 6, 0xFFFFFFFF);
        // Which key opens the menu, where there is room for it.
        if (doneY - 14 >= categoryY(CATEGORIES.length) + 2) {
            String key = OctoClient.keyName(config().menuKey) + " opens this";
            g.text(font, fit(key, SIDEBAR - 20), panelX + 12, doneY - 14, Theme.FAINT, false);
        }
    }

    private void content(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = contentX();
        int w = contentW();
        g.text(font, CATEGORIES[category], x, panelY + 16, Theme.TEXT, false);
        g.text(font, fit(SUBTITLES[category], w), x, panelY + 28, Theme.FAINT, false);

        List<Row> rows = rows();
        scroll = Math.max(0, Math.min(scroll, maxScroll(rows.size())));
        g.enableScissor(x - 2, listTop(), x + w + 2, listBottom());

        for (int i = 0; i < rows.size(); i++) {
            int y = rowY(i);

            if (y + ROW < listTop() || y > listBottom()) {
                continue;
            }

            row(g, rows.get(i), x, y, w, mouseX, mouseY);
        }

        g.disableScissor();

        // A thin bar showing where the list is scrolled to.
        int max = maxScroll(rows.size());

        if (max > 0) {
            int track = listBottom() - listTop();
            int thumb = Math.max(20, track * track / (track + max));
            int top = listTop() + (int) ((track - thumb) * (scroll / max));
            Draw.round(g, x + w + 6, top, 3, thumb, 1, Theme.alpha(Theme.MUTED, 120));
        }
    }

    private void row(GuiGraphicsExtractor g, Row row, int x, int y, int w, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, w, ROW) && mouseY >= listTop() && mouseY < listBottom();
        Draw.round(g, x, y, w, ROW, 7, hover ? Theme.mix(Theme.PANEL, Theme.HOVER, 0.6f) : Theme.PANEL);
        int controlWidth = switch (row) {
            case Toggle t -> 24;
            case Choice c -> 96;
            case Slider s -> 128;
            case Action a -> font.width(a.button()) + 20;
            case KeyRow k -> 70;
        };
        int textWidth = w - controlWidth - 34;
        g.text(font, fit(row.title(), textWidth), x + 10, y + 7, Theme.TEXT, false);
        g.text(font, fit(row.hint(), textWidth), x + 10, y + 19, Theme.FAINT, false);
        int right = controlRight();
        int mid = y + ROW / 2;

        switch (row) {
            case Toggle t -> toggle(g, right - 24, mid - 6, t.get().getAsBoolean());
            case Choice c -> {
                int cx = right - 96;
                Draw.round(g, cx, mid - 8, 96, 16, 5, Theme.RAISED);
                boolean leftHover = inside(mouseX, mouseY, cx, mid - 8, 20, 16);
                boolean rightHover = inside(mouseX, mouseY, cx + 76, mid - 8, 20, 16);
                g.text(font, "<", cx + 7, mid - 4, leftHover ? Theme.TEXT : Theme.FAINT, false);
                g.text(font, ">", cx + 84, mid - 4, rightHover ? Theme.TEXT : Theme.FAINT, false);
                String value = c.options().get(Math.max(0, Math.min(c.options().size() - 1, c.get().getAsInt())));
                g.centeredText(font, fit(value, 56), cx + 48, mid - 4, Theme.TEXT);
            }
            case Slider s -> {
                int value = s.get().getAsInt();
                String label = s.format().apply(value);
                int trackX = right - 84;
                int trackW = 84;
                g.text(font, label, trackX - 8 - font.width(label), mid - 4, Theme.MUTED, false);
                Draw.round(g, trackX, mid - 2, trackW, 4, 2, Theme.RAISED);
                float t = (value - s.min()) / (float) (s.max() - s.min());
                int filled = Math.round(trackW * t);

                if (filled > 0) {
                    Draw.gradient(g, trackX, mid - 2, Math.max(4, filled), 4, 2, Theme.accent(), Theme.accentEnd());
                }

                int knob = trackX + filled;
                Draw.round(g, knob - 5, mid - 5, 10, 10, 5, 0xFFFFFFFF);
            }
            case Action a -> {
                int bw = font.width(a.button()) + 20;
                boolean buttonHover = inside(mouseX, mouseY, right - bw, mid - 8, bw, 16);
                Draw.round(g, right - bw, mid - 8, bw, 16, 5, buttonHover ? Theme.HOVER : Theme.RAISED);
                Draw.outline(g, right - bw, mid - 8, bw, 16, 5, buttonHover ? Theme.accent() : Theme.LINE);
                g.centeredText(font, a.button(), right - bw / 2, mid - 4, Theme.TEXT);
            }
            case KeyRow k -> {
                boolean listening = capturing != null && capturing.title().equals(k.title());
                Draw.round(g, right - 70, mid - 8, 70, 16, 5, listening ? Theme.alpha(Theme.accent(), 70)
                        : Theme.RAISED);
                Draw.outline(g, right - 70, mid - 8, 70, 16, 5, listening ? Theme.accent() : Theme.LINE);
                g.centeredText(font, fit(listening ? "Press a key" : OctoClient.keyName(k.get().getAsInt()), 62),
                        right - 35, mid - 4, Theme.TEXT);
            }
        }
    }

    private static void toggle(GuiGraphicsExtractor g, int x, int y, boolean on) {
        if (on) {
            Draw.gradient(g, x, y, 24, 12, 6, Theme.accent(), Theme.accentEnd());
        } else {
            Draw.round(g, x, y, 24, 12, 6, Theme.HOVER);
        }

        Draw.round(g, on ? x + 14 : x + 2, y + 2, 8, 8, 4, 0xFFFFFFFF);
    }

    /** Text cut to a width, ending in an ellipsis when it was. */
    private String fit(String text, int width) {
        if (text == null) {
            return "";
        }

        if (font.width(text) <= width) {
            return text;
        }

        String cut = text;

        while (!cut.isEmpty() && font.width(cut + "...") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }

        return cut.stripTrailing() + "...";
    }

    // ------------------------------------------------------------ input

    private void click() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.2f, 0.35f));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y() - 0;

        // The sidebar: categories, and Done.
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (inside(mx, my, panelX + 8, categoryY(i), SIDEBAR - 16, categoryHeight() - 4)) {
                if (category != i) {
                    category = i;
                    scroll = 0;
                    capturing = null;
                    click();
                }

                return true;
            }
        }

        if (inside(mx, my, panelX + 8, panelY + panelH - 32, SIDEBAR - 16, 20)) {
            click();
            onClose();
            return true;
        }

        if (my < listTop() || my >= listBottom()) {
            return inside(mx, my, panelX, panelY, panelW, panelH) || super.mouseClicked(event, doubleClick);
        }

        List<Row> rows = rows();
        int right = controlRight();

        for (int i = 0; i < rows.size(); i++) {
            int y = rowY(i);

            if (!inside(mx, my, contentX(), y, contentW(), ROW)) {
                continue;
            }

            int mid = y + ROW / 2;

            switch (rows.get(i)) {
                case Toggle t -> {
                    t.set().accept(!t.get().getAsBoolean());
                    click();
                }
                case Choice c -> {
                    int count = c.options().size();
                    boolean back = inside(mx, my, right - 96, mid - 8, 48, 16);
                    c.set().accept(Math.floorMod(c.get().getAsInt() + (back ? -1 : 1), count));
                    click();
                }
                case Slider s -> {
                    if (inside(mx, my, right - 90, mid - 8, 96, 16)) {
                        dragging = s;
                        slide(s, mx);
                    }
                }
                case Action a -> {
                    int bw = font.width(a.button()) + 20;

                    if (inside(mx, my, right - bw, mid - 8, bw, 16)) {
                        a.run().run();
                        click();
                    }
                }
                case KeyRow k -> {
                    capturing = k;
                    click();
                }
            }

            config().save();
            return true;
        }

        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging != null) {
            slide(dragging, event.x());
            return true;
        }

        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            config().save();
            return true;
        }

        return super.mouseReleased(event);
    }

    private void slide(Slider slider, double mouseX) {
        int trackX = controlRight() - 84;
        double t = Math.max(0, Math.min(1, (mouseX - trackX) / 84.0));
        int raw = slider.min() + (int) Math.round(t * (slider.max() - slider.min()));
        int stepped = slider.min() + Math.round((raw - slider.min()) / (float) slider.step()) * slider.step();
        slider.set().accept(Math.max(slider.min(), Math.min(slider.max(), stepped)));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(maxScroll(rows().size()), scroll - scrollY * 20));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (capturing != null) {
            if (event.key() != InputConstants.KEY_ESCAPE) {
                capturing.set().accept(event.key());
                config().save();
            }

            capturing = null;
            return true;
        }

        if (event.key() == config().menuKey) {
            onClose();
            return true;
        }

        return super.keyPressed(event);
    }
}
