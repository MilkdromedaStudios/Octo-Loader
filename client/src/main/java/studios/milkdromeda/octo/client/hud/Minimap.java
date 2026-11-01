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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import studios.milkdromeda.octo.client.Config;
import studios.milkdromeda.octo.client.OctoClient;
import studios.milkdromeda.octo.client.ui.Draw;
import studios.milkdromeda.octo.client.ui.Theme;

/**
 * A north-up map of the ground around the player, coloured the way
 * Minecraft's own maps are, shaded by height.
 *
 * <p>It is drawn into one texture a band of rows each tick, so a full
 * refresh is spread over many ticks and never costs a frame.
 */
public final class Minimap {
    private static final int SIZE = 128;
    private static final int ROWS_PER_TICK = 8;
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("octo-client", "minimap");
    private static final int[] DISPLAY = { 72, 96, 128 };

    private static DynamicTexture texture;
    private static int nextRow;
    private static int centreX;
    private static int centreZ;
    private static int scale = 1;

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

            if (nextRow == 0) {
                // A new pass starts where the player is now.
                centreX = player.getBlockX();
                centreZ = player.getBlockZ();
                scale = 1 << config.minimapScale;
            }

            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            boolean ceiling = level.dimensionType().hasCeiling();
            int playerY = player.getBlockY();

            for (int row = nextRow; row < Math.min(SIZE, nextRow + ROWS_PER_TICK); row++) {
                int z = centreZ + (row - SIZE / 2) * scale;

                for (int column = 0; column < SIZE; column++) {
                    int x = centreX + (column - SIZE / 2) * scale;
                    pixels.setPixel(column, row, colour(level, pos, x, z, ceiling, playerY));
                }
            }

            nextRow += ROWS_PER_TICK;

            if (nextRow >= SIZE) {
                nextRow = 0;
                texture.upload();
            }
        } catch (RuntimeException e) {
            // A level changing under it: the next tick starts again.
            nextRow = 0;
        }
    }

    /** The colour of the top of one column of the world. */
    private static int colour(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int z, boolean ceiling,
            int playerY) {
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return 0xFF0A0C11;
        }

        int y;

        if (ceiling) {
            // The Nether: the first solid block below the player, not the roof.
            y = playerY + 1;
            pos.set(x, y, z);

            while (y > level.getMinY() && level.getBlockState(pos).isAir() && playerY - y < 48) {
                pos.setY(--y);
            }
        } else {
            y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            pos.set(x, y, z);
        }

        BlockState state = level.getBlockState(pos);
        MapColor mapColour = state.getMapColor(level, pos);

        if (mapColour == MapColor.NONE) {
            return 0xFF0A0C11;
        }

        // Shade by height against the block to the north, as Minecraft's maps do.
        int north = ceiling ? y : level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - 1) - 1;
        MapColor.Brightness brightness = y > north ? MapColor.Brightness.HIGH
                : y < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        return mapColour.calculateARGBColor(brightness) | 0xFF000000;
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

        int size = DISPLAY[config.minimapSize];
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
        g.pose().pushMatrix();
        g.pose().translate(px, pz);
        g.pose().rotate((float) Math.toRadians(player.getYRot() + 180));
        arrow(g);
        g.pose().popMatrix();

        Font font = minecraft.font;
        g.centeredText(font, "N", x + size / 2, mapY + 2, Theme.TEXT);
        int bottom = mapY + size + frame;

        if (config.minimapCoordinates) {
            String text = player.getBlockX() + ", " + player.getBlockY() + ", " + player.getBlockZ();
            int w = Math.max(size + frame * 2, font.width(text) + 12);
            Draw.round(g, right + frame - w, bottom + 3, w, 14, 5, Theme.alpha(Theme.WINDOW, 200));
            g.centeredText(font, text, right + frame - w / 2, bottom + 6, Theme.TEXT);
            bottom += 17;
        }

        return bottom;
    }

    /** A small arrow pointing up, centred on the origin, row by row. */
    private static void arrow(GuiGraphicsExtractor g) {
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
