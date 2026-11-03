package studios.milkdromeda.octo.client.hud;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import studios.milkdromeda.octo.client.Config;
import studios.milkdromeda.octo.client.OctoClient;
import studios.milkdromeda.octo.client.map.Terrain;
import studios.milkdromeda.octo.client.map.Waypoints;
import studios.milkdromeda.octo.client.ui.Draw;
import studios.milkdromeda.octo.client.ui.Theme;

/**
 * A north-up map of the ground around the player, coloured the way
 * Minecraft's own maps are: the surface, shaded by height, or the cave the
 * player is in (see {@link Terrain}), with their waypoints on it.
 *
 * <p>It is drawn into one texture a band of rows each tick, so a full
 * refresh is spread over many ticks and never costs a frame.
 */
public final class Minimap {
    private static final int SIZE = 128;
    private static final int ROWS_PER_TICK = 8;
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("octo-client", "minimap");

    private static DynamicTexture texture;
    private static int nextRow;
    private static int centreX;
    private static int centreZ;
    private static int scale = 1;
    /** The left edge of what it last drew, for what sits beside it. */
    private static int drawnLeft;
    /** The layer and height the current pass is drawn for. */
    private static Terrain.Layer passLayer = Terrain.Layer.SURFACE;
    private static int passY;
    /** The layer of the picture now on screen. */
    private static Terrain.Layer shownLayer = Terrain.Layer.SURFACE;

    private Minimap() {
    }

    /** Every tick: draws the next band of rows. */
    public static void tick(Minecraft minecraft) {
        Config config = OctoClient.config();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;

        if (!config.minimap || player == null || level == null) {
            return;
        }

        try {
            if (texture == null) {
                texture = new DynamicTexture(() -> "Octo minimap", new NativeImage(SIZE, SIZE, true));
                minecraft.getTextureManager().register(TEXTURE, texture);
            }

            NativeImage pixels = texture.getPixels();

            if (pixels == null) {
                return;
            }

            Terrain.Layer layer = Terrain.layer(minecraft);

            if (nextRow > 0 && layer != passLayer) {
                // Into a cave or out of one: the map changes now, not at the end of this pass.
                nextRow = 0;
            }

            if (nextRow == 0) {
                // A new pass starts where the player is now.
                centreX = player.getBlockX();
                centreZ = player.getBlockZ();
                scale = 1 << config.minimapScale;
                passLayer = layer;
                passY = player.getBlockY();
            }

            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            for (int row = nextRow; row < Math.min(SIZE, nextRow + ROWS_PER_TICK); row++) {
                int z = centreZ + (row - SIZE / 2) * scale;

                for (int column = 0; column < SIZE; column++) {
                    int x = centreX + (column - SIZE / 2) * scale;
                    pixels.setPixel(column, row, Terrain.colour(level, pos, x, z, passLayer, passY));
                }
            }

            nextRow += ROWS_PER_TICK;

            if (nextRow >= SIZE) {
                nextRow = 0;
                texture.upload();
                shownLayer = passLayer;
            }
        } catch (RuntimeException e) {
            // A level changing under it: the next tick starts again.
            nextRow = 0;
        }
    }

    /**
     * Draws the map at the top right.
     *
     * @return the y below what it drew
     */
    public static int extract(GuiGraphicsExtractor g, Minecraft minecraft, int right, int top) {
        Config config = OctoClient.config();
        LocalPlayer player = minecraft.player;

        if (texture == null || player == null) {
            return top - 6;
        }

        int size = config.minimapPixels;
        int x = right - size;
        int frame = 3;
        Draw.round(g, x - frame, top, size + frame * 2, size + frame * 2, 8,
                Theme.alpha(Theme.WINDOW, 200));
        int mapY = top + frame;
        g.blit(TEXTURE, x, mapY, x + size, mapY + size, 0f, 1f, 0f, 1f);
        Draw.outline(g, x - frame, top, size + frame * 2, size + frame * 2, 8, Theme.alpha(Theme.accent(), 140));

        // The player: where they are against the map's centre, pointing where they look.
        float pixelsPerBlock = size / (float) (SIZE * scale);
        float px = x + size / 2f + (float) (player.getX() - centreX) * pixelsPerBlock;
        float pz = mapY + size / 2f + (float) (player.getZ() - centreZ) * pixelsPerBlock;
        if (config.waypointsOnMap) {
            waypoints(g, minecraft, x, mapY, size, pixelsPerBlock);
        }

        g.pose().pushMatrix();
        g.pose().translate(px, pz);
        g.pose().rotate((float) Math.toRadians(player.getYRot() + 180));
        arrow(g);
        g.pose().popMatrix();

        Font font = minecraft.font;

        g.centeredText(font, "N", x + size / 2, mapY + 2, Theme.TEXT);

        if (shownLayer == Terrain.Layer.CAVES) {
            String label = "Caves";
            int lw = font.width(label) + 8;
            Draw.round(g, x + 3, mapY + size - 14, lw, 11, 4, Theme.alpha(Theme.WINDOW, 210));
            g.text(font, label, x + 7, mapY + size - 12, Theme.MUTED, false);
        }
        int bottom = mapY + size + frame;

        if (config.minimapCoordinates) {
            String text = player.getBlockX() + ", " + player.getBlockY() + ", " + player.getBlockZ();
            int w = Math.max(size + frame * 2, font.width(text) + 12);
            Draw.round(g, right + frame - w, bottom + 3, w, 14, 5, Theme.alpha(Theme.WINDOW, 200));
            g.centeredText(font, text, right + frame - w / 2, bottom + 6, Theme.TEXT);
            bottom += 17;
            drawnLeft = Math.min(x - frame, right + frame - w);
        } else {
            drawnLeft = x - frame;
        }

        return bottom;
    }

    /**
     * The waypoints on the map: a diamond at each, or a dot at the edge in its
     * direction when it is further away than the map reaches.
     */
    private static void waypoints(GuiGraphicsExtractor g, Minecraft minecraft, int x, int mapY, int size,
            float pixelsPerBlock) {
        float cx = x + size / 2f;
        float cy = mapY + size / 2f;
        float reach = size / 2f - 4;

        for (Waypoints.Waypoint waypoint : Waypoints.here(minecraft)) {
            if (!waypoint.visible) {
                continue;
            }

            float dx = (waypoint.x + 0.5f - centreX) * pixelsPerBlock;
            float dz = (waypoint.z + 0.5f - centreZ) * pixelsPerBlock;
            float far = Math.max(Math.abs(dx), Math.abs(dz));

            if (far > reach) {
                dx = dx / far * reach;
                dz = dz / far * reach;
                int px = Math.round(cx + dx);
                int pz = Math.round(cy + dz);
                g.fill(px - 2, pz - 2, px + 2, pz + 2, 0xFF000000);
                g.fill(px - 1, pz - 1, px + 1, pz + 1, waypoint.colour);
            } else {
                diamond(g, Math.round(cx + dx), Math.round(cy + dz), 3, waypoint.colour);
            }
        }
    }

    /** The left edge of the map as last drawn. */
    public static int drawnLeft() {
        return drawnLeft;
    }

    /** A diamond with a dark edge, centred on x, y. */
    public static void diamond(GuiGraphicsExtractor g, int x, int y, int radius, int colour) {
        for (int row = -radius - 1; row <= radius + 1; row++) {
            int half = radius + 1 - Math.abs(row);
            g.fill(x - half, y + row, x + half + 1, y + row + 1, 0xFF000000);
        }

        for (int row = -radius; row <= radius; row++) {
            int half = radius - Math.abs(row);
            g.fill(x - half, y + row, x + half + 1, y + row + 1, colour);
        }
    }

    /** A small arrow pointing up, centred on the origin, row by row. */
    public static void arrow(GuiGraphicsExtractor g) {
        for (int row = 0; row < 7; row++) {
            int half = row / 2 + 1;
            g.fill(-half, row - 4, half, row - 3, 0xFF000000);
        }

        for (int row = 0; row < 6; row++) {
            int half = (row + 1) / 2;
            g.fill(-half, row - 3, Math.max(1, half), row - 2, 0xFFFFFFFF);
        }
    }

    /** Forgets the texture; it is made again when needed (a resource reload drops it). */
    public static void reset() {
        texture = null;
        nextRow = 0;
    }
}
