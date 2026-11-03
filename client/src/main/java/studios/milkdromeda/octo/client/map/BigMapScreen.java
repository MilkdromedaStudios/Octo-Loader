package studios.milkdromeda.octo.client.map;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.levelgen.Heightmap;

import studios.milkdromeda.octo.client.OctoClient;
import studios.milkdromeda.octo.client.hud.Minimap;
import studios.milkdromeda.octo.client.ui.Draw;
import studios.milkdromeda.octo.client.ui.Theme;

/**
 * The minimap, opened up to fill the screen: everywhere the player has been
 * this session, their waypoints, and a panel to keep them in order. Drag to
 * move about, scroll to zoom, right-click to set a waypoint.
 */
public final class BigMapScreen extends Screen {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("octo-client", "big_map");
    private static final int TEX = 512;
    private static final float MIN_ZOOM = 0.125f;
    private static final float MAX_ZOOM = 8f;
    private static final int PANEL_W = 168;
    private static final int ROW_H = 18;

    private static DynamicTexture texture;
    /** Kept between openings: the map opens where it was left, at the zoom it had. */
    private static float zoom = 2f;

    private double centreX;
    private double centreZ;
    private boolean follow = true;
    private boolean dragging;
    private boolean moved;

    /** The area the texture holds now, in blocks, and how many texels of it are used. */
    private double builtLeft;
    private double builtTop;
    private double builtSide;
    private int builtRes;
    private long builtAt;
    private double builtCentreX = Double.NaN;
    private double builtCentreZ;
    private float builtZoom;

    /** Whether the key that opened it has been let go: until then, held and repeating, it does not close it. */
    private boolean mapKeyReleased;
    private Waypoints.Waypoint selected;
    private EditBox name;
    private int listScroll;

    public BigMapScreen() {
        super(Component.literal("Map"));
    }

    @Override
    protected void init() {
        LocalPlayer player = minecraft.player;

        if (player != null && follow) {
            centreX = player.getX();
            centreZ = player.getZ();
        }

        name = new EditBox(font, 0, 0, PANEL_W - 24, 16, Component.literal("Name"));
        name.setMaxLength(48);
        name.setResponder(text -> {
            if (selected != null && !text.isBlank()) {
                selected.name = text.strip();
            }
        });
        name.setVisible(false);
        addRenderableWidget(name);
        select(selected);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Waypoints.save(minecraft);
        super.onClose();
    }

    @Override
    public void tick() {
        mapKeyReleased |= !InputConstants.isKeyDown(OctoClient.config().mapKey);
        LocalPlayer player = minecraft.player;

        if (player == null) {
            onClose();
            return;
        }

        if (follow) {
            centreX = player.getX();
            centreZ = player.getZ();
        }
    }

    // ------------------------------------------------------------ layout

    private int editTop() {
        return height - 100;
    }

    private int listTop() {
        return 40;
    }

    private int listBottom() {
        return selected != null ? editTop() - 8 : height - 34;
    }

    private void select(Waypoints.Waypoint waypoint) {
        selected = waypoint;

        if (name == null) {
            return;
        }

        name.setVisible(waypoint != null);

        if (waypoint != null) {
            name.setX(16);
            name.setY(editTop() + 22);
            name.setValue(waypoint.name);
        } else {
            name.setFocused(false);
            setFocused(null);
        }
    }

    // --------------------------------------------------------- the map

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, Terrain.UNKNOWN);
        build();

        if (builtRes > 0) {
            int x = (int) Math.round(width / 2.0 + (builtLeft - centreX) * zoom);
            int y = (int) Math.round(height / 2.0 + (builtTop - centreZ) * zoom);
            int size = (int) Math.round(builtSide * zoom);
            float uv = builtRes / (float) TEX;
            g.blit(TEXTURE, x, y, x + size, y + size, 0f, uv, 0f, uv);
        }

        // Waypoints, then the player on top.
        for (Waypoints.Waypoint waypoint : Waypoints.here(minecraft)) {
            if (!waypoint.visible && waypoint != selected) {
                continue;
            }

            int x = screenX(waypoint.x + 0.5);
            int y = screenY(waypoint.z + 0.5);

            if (waypoint == selected) {
                Draw.round(g, x - 8, y - 8, 16, 16, 8, Theme.alpha(0xFFFFFFFF, 90));
            }

            Minimap.diamond(g, x, y, 4, waypoint.visible ? waypoint.colour : Theme.alpha(waypoint.colour, 110));

            if (zoom >= 0.5f || waypoint == selected) {
                int w = font.width(waypoint.name) + 8;
                Draw.round(g, x - w / 2, y + 8, w, 11, 4, Theme.alpha(Theme.WINDOW, 200));
                g.text(font, waypoint.name, x - w / 2 + 4, y + 10, Theme.TEXT, false);
            }
        }

        LocalPlayer player = minecraft.player;

        if (player != null) {
            g.pose().pushMatrix();
            g.pose().translate((float) (width / 2.0 + (player.getX() - centreX) * zoom),
                    (float) (height / 2.0 + (player.getZ() - centreZ) * zoom));
            g.pose().rotate((float) Math.toRadians(player.getYRot() + 180));
            g.pose().scale(1.5f, 1.5f);
            Minimap.arrow(g);
            g.pose().popMatrix();
        }
    }

    /** Fills the texture with the part of the world on screen, when it has moved or every half second. */
    private void build() {
        long now = System.currentTimeMillis();
        boolean changed = centreX != builtCentreX || centreZ != builtCentreZ || zoom != builtZoom;

        if (!(changed && now - builtAt > 40) && now - builtAt < 500) {
            return;
        }

        try {
            if (texture == null) {
                texture = new DynamicTexture(() -> "Octo big map", new NativeImage(TEX, TEX, true));
                minecraft.getTextureManager().register(TEXTURE, texture);
            }

            NativeImage pixels = texture.getPixels();

            if (pixels == null) {
                return;
            }

            // A square a little larger than the screen, so a small drag needs no rebuild to look right.
            double side = Math.max(width, height) / zoom * 1.25;
            int res = (int) Math.min(TEX, Math.ceil(side));
            double perTexel = side / res;
            double left = centreX - side / 2;
            double top = centreZ - side / 2;

            for (int j = 0; j < res; j++) {
                int z = (int) Math.floor(top + (j + 0.5) * perTexel);

                for (int i = 0; i < res; i++) {
                    pixels.setPixel(i, j, MapCache.colour((int) Math.floor(left + (i + 0.5) * perTexel), z));
                }
            }

            texture.upload();
            builtLeft = left;
            builtTop = top;
            builtSide = side;
            builtRes = res;
            builtAt = now;
            builtCentreX = centreX;
            builtCentreZ = centreZ;
            builtZoom = zoom;
        } catch (RuntimeException e) {
            texture = null;
            builtRes = 0;
        }
    }

    private int screenX(double worldX) {
        return (int) Math.round(width / 2.0 + (worldX - centreX) * zoom);
    }

    private int screenY(double worldZ) {
        return (int) Math.round(height / 2.0 + (worldZ - centreZ) * zoom);
    }

    private double worldX(double screenX) {
        return centreX + (screenX - width / 2.0) / zoom;
    }

    private double worldZ(double screenY) {
        return centreZ + (screenY - height / 2.0) / zoom;
    }

    // ---------------------------------------------------------- panels

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        topBar(g);
        list(g, mouseX, mouseY);

        if (selected != null) {
            editor(g, mouseX, mouseY);
        }

        hints(g, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void topBar(GuiGraphicsExtractor g) {
        String title = "Map";
        String detail = detail();
        int w = font.width(title) + font.width(detail) + 34;
        Draw.round(g, 8, 8, w, 22, 7, Theme.alpha(Theme.WINDOW, 225));
        Draw.gradient(g, 14, 14, 10, 10, 3, Theme.accent(), Theme.accentEnd());
        g.text(font, title, 30, 15, Theme.TEXT, false);
        g.text(font, detail, 30 + font.width(title) + 8, 15, Theme.MUTED, false);

        // Beside the title, clear of Minecraft's own pop-ups at the top right.
        String centre = follow ? "Following you" : "Centre on me";
        int cw = font.width(centre) + 16;
        int cx = centreButtonX();
        Draw.round(g, cx, 8, cw, 22, 7, Theme.alpha(follow ? Theme.WINDOW : Theme.accent(), 225));
        g.text(font, centre, cx + 8, 15, follow ? Theme.MUTED : 0xFFFFFFFF, false);
    }

    private String detail() {
        String dimension = minecraft.level == null ? "" : pretty(minecraft.level.dimension().identifier().getPath());
        return dimension + "  ·  " + MapCache.layer().title + (OctoClient.config().minimapLayer == 0
                && minecraft.level != null && !minecraft.level.dimensionType().hasCeiling() ? " (auto)" : "");
    }

    private int centreButtonX() {
        return 8 + font.width("Map") + font.width(detail()) + 34 + 6;
    }

    private void list(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<Waypoints.Waypoint> waypoints = Waypoints.here(minecraft);
        int top = listTop();
        int bottom = listBottom();
        Draw.round(g, 8, top, PANEL_W, bottom - top, 8, Theme.alpha(Theme.WINDOW, 225));
        g.text(font, "Waypoints", 16, top + 8, Theme.TEXT, false);
        String add = "+ Here";
        int aw = font.width(add) + 12;
        boolean overAdd = inside(mouseX, mouseY, 8 + PANEL_W - aw - 6, top + 4, aw, 16);
        Draw.round(g, 8 + PANEL_W - aw - 6, top + 4, aw, 16, 5, overAdd ? Theme.accentEnd() : Theme.accent());
        g.text(font, add, 8 + PANEL_W - aw, top + 8, 0xFFFFFFFF, false);

        int rowsTop = top + 26;
        int visible = Math.max(0, (bottom - rowsTop - 4) / ROW_H);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, waypoints.size() - visible)));

        if (waypoints.isEmpty()) {
            g.text(font, "None yet.", 16, rowsTop + 2, Theme.MUTED, false);
            g.text(font, "Right-click the map,", 16, rowsTop + 14, Theme.MUTED, false);
            g.text(font, "or press " + OctoClient.keyName(OctoClient.config().waypointKey) + " in game.", 16,
                    rowsTop + 26, Theme.MUTED, false);
        }

        LocalPlayer player = minecraft.player;

        for (int i = 0; i < visible && i + listScroll < waypoints.size(); i++) {
            Waypoints.Waypoint waypoint = waypoints.get(i + listScroll);
            int y = rowsTop + i * ROW_H;
            boolean over = inside(mouseX, mouseY, 12, y, PANEL_W - 8, ROW_H - 2);

            if (waypoint == selected || over) {
                Draw.round(g, 12, y, PANEL_W - 8, ROW_H - 2, 5, waypoint == selected ? Theme.alpha(Theme.accent(), 70)
                        : Theme.HOVER);
            }

            Minimap.diamond(g, 22, y + 8, 3, waypoint.visible ? waypoint.colour : Theme.alpha(waypoint.colour, 110));
            String distance = player == null ? ""
                    : WaypointMarkers.distance(waypoint.distanceTo(player.getX(), player.getY(), player.getZ()));
            int dw = font.width(distance);
            g.text(font, fit(waypoint.name, PANEL_W - 46 - dw), 32, y + 4,
                    waypoint.visible ? Theme.TEXT : Theme.MUTED, false);
            g.text(font, distance, 8 + PANEL_W - 8 - dw, y + 4, Theme.MUTED, false);
        }
    }

    private void editor(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int top = editTop();
        Draw.round(g, 8, top, PANEL_W, 92, 8, Theme.alpha(Theme.WINDOW, 235));
        g.text(font, selected.death ? "Where you died" : "Waypoint", 16, top + 8, Theme.MUTED, false);
        String where = selected.x + ", " + selected.y + ", " + selected.z;
        g.text(font, where, 8 + PANEL_W - 8 - font.width(where), top + 8, Theme.MUTED, false);

        // Colours.
        for (int i = 0; i < Waypoints.COLOURS.length; i++) {
            int x = 16 + i * 18;
            int y = top + 46;
            boolean chosen = Waypoints.COLOURS[i] == selected.colour;

            if (chosen) {
                Draw.round(g, x - 2, y - 2, 16, 16, 5, 0xFFFFFFFF);
            }

            Draw.round(g, x, y, 12, 12, 4, Waypoints.COLOURS[i]);
        }

        button(g, mouseX, mouseY, 16, top + 70, 46, selected.visible ? "Hide" : "Show", false);
        button(g, mouseX, mouseY, 66, top + 70, 46, "Go to", false);
        button(g, mouseX, mouseY, 116, top + 70, 52, "Delete", true);
    }

    private void button(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y, int w, String label,
            boolean danger) {
        boolean over = inside(mouseX, mouseY, x, y, w, 16);
        int colour = danger ? (over ? Theme.RED : Theme.alpha(Theme.RED, 170)) : over ? Theme.HOVER : Theme.RAISED;
        Draw.round(g, x, y, w, 16, 5, colour);
        g.centeredText(font, label, x + w / 2, y + 4, danger ? 0xFFFFFFFF : Theme.TEXT);
    }

    private void hints(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String under = (int) Math.floor(worldX(mouseX)) + ", " + (int) Math.floor(worldZ(mouseY));
        int room = width - PANEL_W - 24;
        String text = "Drag to move  ·  Scroll to zoom  ·  Right-click to set a waypoint  ·  " + under;

        if (font.width(text) + 16 > room) {
            text = "Drag · Scroll · Right-click: waypoint  ·  " + under;
        }

        if (font.width(text) + 16 > room) {
            text = under;
        }

        int w = font.width(text) + 16;
        int x = Math.max(PANEL_W + 16, (width - w) / 2);
        Draw.round(g, x, height - 22, w, 16, 6, Theme.alpha(Theme.WINDOW, 210));
        g.text(font, text, x + 8, height - 18, Theme.MUTED, false);
    }

    private String fit(String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }

        int end = text.length();

        while (end > 0 && font.width(text.substring(0, end) + "…") > width) {
            end--;
        }

        return text.substring(0, end) + "…";
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

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

    private void click() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.2f, 0.35f));
    }

    // ----------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();

        if (name.visible && name.isMouseOver(mx, my)) {
            return super.mouseClicked(event, doubleClick);
        }

        name.setFocused(false);
        setFocused(null);

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && clickedPanel(mx, my)) {
            return true;
        }

        if (mx < 8 + PANEL_W && my >= listTop() && my < (selected != null ? editTop() + 92 : listBottom())) {
            return true; // inside a panel, on nothing
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            setWaypointAt(mx, my);
            return true;
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            Waypoints.Waypoint hit = waypointAt(mx, my);

            if (hit != null) {
                select(hit);
                click();
                return true;
            }

            dragging = true;
            moved = false;
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    /** The top bar and the waypoint panels; true when the click was theirs. */
    private boolean clickedPanel(double mx, double my) {
        String centre = follow ? "Following you" : "Centre on me";
        int cw = font.width(centre) + 16;

        if (inside(mx, my, centreButtonX(), 8, cw, 22)) {
            follow = true;
            click();
            return true;
        }

        int top = listTop();
        int aw = font.width("+ Here") + 12;

        if (inside(mx, my, 8 + PANEL_W - aw - 6, top + 4, aw, 16) && minecraft.player != null) {
            LocalPlayer player = minecraft.player;
            select(Waypoints.add(minecraft, player.getBlockX(), player.getBlockY(), player.getBlockZ()));
            click();
            return true;
        }

        List<Waypoints.Waypoint> waypoints = Waypoints.here(minecraft);
        int rowsTop = top + 26;
        int visible = Math.max(0, (listBottom() - rowsTop - 4) / ROW_H);

        for (int i = 0; i < visible && i + listScroll < waypoints.size(); i++) {
            if (inside(mx, my, 12, rowsTop + i * ROW_H, PANEL_W - 8, ROW_H - 2)) {
                Waypoints.Waypoint waypoint = waypoints.get(i + listScroll);
                select(waypoint);
                follow = false;
                centreX = waypoint.x + 0.5;
                centreZ = waypoint.z + 0.5;
                click();
                return true;
            }
        }

        if (selected == null) {
            return false;
        }

        int etop = editTop();

        for (int i = 0; i < Waypoints.COLOURS.length; i++) {
            if (inside(mx, my, 14 + i * 18, etop + 44, 16, 16)) {
                selected.colour = Waypoints.COLOURS[i];
                Waypoints.save(minecraft);
                click();
                return true;
            }
        }

        if (inside(mx, my, 16, etop + 70, 46, 16)) {
            selected.visible = !selected.visible;
            Waypoints.save(minecraft);
            click();
            return true;
        }

        if (inside(mx, my, 66, etop + 70, 46, 16)) {
            follow = false;
            centreX = selected.x + 0.5;
            centreZ = selected.z + 0.5;
            click();
            return true;
        }

        if (inside(mx, my, 116, etop + 70, 52, 16)) {
            Waypoints.remove(minecraft, selected);
            select(null);
            click();
            return true;
        }

        return false;
    }

    private Waypoints.Waypoint waypointAt(double mx, double my) {
        Waypoints.Waypoint best = null;
        double bestDistance = 8;

        for (Waypoints.Waypoint waypoint : Waypoints.here(minecraft)) {
            double d = Math.hypot(screenX(waypoint.x + 0.5) - mx, screenY(waypoint.z + 0.5) - my);

            if (d < bestDistance) {
                best = waypoint;
                bestDistance = d;
            }
        }

        return best;
    }

    private void setWaypointAt(double mx, double my) {
        int x = (int) Math.floor(worldX(mx));
        int z = (int) Math.floor(worldZ(my));
        int y = minecraft.player == null ? 64 : minecraft.player.getBlockY();

        if (minecraft.level != null && minecraft.level.hasChunk(x >> 4, z >> 4)
                && MapCache.layer() == Terrain.Layer.SURFACE) {
            y = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }

        Waypoints.Waypoint waypoint = Waypoints.add(minecraft, x, y, z);
        select(waypoint);
        setFocused(name);
        name.setFocused(true);
        click();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging) {
            follow = false;
            moved = true;
            centreX -= dragX / zoom;
            centreZ -= dragY / zoom;
            return true;
        }

        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;

            if (!moved && selected != null) {
                select(null);
            }

            return true;
        }

        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < 8 + PANEL_W && mouseY >= listTop() && mouseY < listBottom()) {
            listScroll = Math.max(0, listScroll - (int) Math.signum(scrollY));
            return true;
        }

        if (scrollY == 0) {
            return false;
        }

        // Zoom about the pointer: the block under it stays under it.
        double wx = worldX(mouseX);
        double wz = worldZ(mouseY);
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * (scrollY > 0 ? 1.25f : 0.8f)));

        if (!follow) {
            centreX = wx - (mouseX - width / 2.0) / zoom;
            centreZ = wz - (mouseY - height / 2.0) / zoom;
        }

        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (name.visible && name.isFocused()) {
            if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_RETURN) {
                name.setFocused(false);
                setFocused(null);
                Waypoints.save(minecraft);
                return true;
            }

            return super.keyPressed(event);
        }

        if (event.key() == OctoClient.config().mapKey && mapKeyReleased) {
            onClose();
            return true;
        }

        if (event.key() == InputConstants.KEY_SPACE) {
            follow = true;
            return true;
        }

        return super.keyPressed(event);
    }
}
