package studios.milkdromeda.octo.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import studios.milkdromeda.octo.client.Config;
import studios.milkdromeda.octo.client.OctoClient;

/**
 * The colours the minimap and the big map are drawn in, and which layer of
 * the world they show.
 *
 * <p>Smart altitude: above ground the map shows the surface, shaded by how
 * high it stands against the player, so a mountain is bright and a valley
 * dark. Underground it shows the cave the player is in: the floor around
 * their level, walls in shadow, drops darker the deeper they go. Which one is
 * decided by whether there is rock overhead and no sky light, held for a
 * second either way so walking under a tree or out of a cave mouth does not
 * flicker.
 */
public final class Terrain {
    /** Not loaded, or nothing there. */
    public static final int UNKNOWN = 0xFF0A0C11;
    /** Solid rock at the player's level, in the cave layer. */
    public static final int WALL = 0xFF1A1D25;

    public enum Layer {
        SURFACE("Surface"), CAVES("Caves");

        public final String title;

        Layer(String title) {
            this.title = title;
        }
    }

    /** Ticks in a row the other layer must be called for before the map changes to it. */
    private static final int SETTLE = 20;

    private static Layer detected = Layer.SURFACE;
    private static int disagreeing;

    private Terrain() {
    }

    /** Every tick: is the player underground? */
    public static void tick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;

        if (player == null || level == null) {
            detected = Layer.SURFACE;
            disagreeing = 0;
            return;
        }

        Layer now = underground(level, player) ? Layer.CAVES : Layer.SURFACE;

        if (now == detected) {
            disagreeing = 0;
        } else if (++disagreeing >= SETTLE) {
            detected = now;
            disagreeing = 0;
        }
    }

    /**
     * Rock well above the head, and almost no light from the sky: a cave, not
     * a forest or a house with a low roof.
     */
    static boolean underground(ClientLevel level, LocalPlayer player) {
        BlockPos head = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, head.getX(), head.getZ());
        return surface > head.getY() + 3 && level.getBrightness(LightLayer.SKY, head) < 8;
    }

    /** The layer the maps show now: the setting, or what the player is in when it is Auto. */
    public static Layer layer(Minecraft minecraft) {
        Config config = OctoClient.config();

        if (minecraft.level != null && minecraft.level.dimensionType().hasCeiling()) {
            // The Nether: a roof over everything, so always the layer the player is in.
            return Layer.CAVES;
        }

        return switch (config.minimapLayer) {
            case 1 -> Layer.SURFACE;
            case 2 -> Layer.CAVES;
            default -> detected;
        };
    }

    /** One column's colour, for a player standing at {@code playerY}. */
    public static int colour(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int z, Layer layer,
            int playerY) {
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return UNKNOWN;
        }

        return layer == Layer.CAVES ? cave(level, pos, x, z, playerY) : surface(level, pos, x, z, playerY);
    }

    private static int surface(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int z, int playerY) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        pos.set(x, y, z);
        BlockState state = level.getBlockState(pos);
        MapColor mapColour = state.getMapColor(level, pos);

        if (mapColour == MapColor.NONE) {
            return UNKNOWN;
        }

        // Shade by the block to the north, as Minecraft's maps do, so slopes show...
        int north = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - 1) - 1;
        MapColor.Brightness brightness = y > north ? MapColor.Brightness.HIGH
                : y < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        // ...and by height against the player, so a mountain stands out and a valley sinks.
        return tint(mapColour.calculateARGBColor(brightness), (y - playerY) / 80f);
    }

    private static int cave(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int z, int playerY) {
        boolean feetOpen = open(level.getBlockState(pos.set(x, playerY, z)));
        boolean headOpen = open(level.getBlockState(pos.set(x, playerY + 1, z)));

        if (!feetOpen) {
            if (!headOpen) {
                return WALL;
            }

            // A step up: the floor is at the player's feet.
            pos.setY(playerY);
            return floor(level, pos, 0);
        }

        int bottom = Math.max(level.getMinY(), playerY - 32);

        for (int y = playerY - 1; y >= bottom; y--) {
            pos.setY(y);

            if (!open(level.getBlockState(pos))) {
                return floor(level, pos, playerY - 1 - y);
            }
        }

        return UNKNOWN;
    }

    /** A cave floor, darker the further below the player it lies. */
    private static int floor(ClientLevel level, BlockPos.MutableBlockPos pos, int depth) {
        BlockState state = level.getBlockState(pos);
        MapColor mapColour = state.getMapColor(level, pos);

        if (mapColour == MapColor.NONE) {
            return WALL;
        }

        MapColor.Brightness brightness = depth <= 0 ? MapColor.Brightness.HIGH
                : depth <= 4 ? MapColor.Brightness.NORMAL
                : depth <= 12 ? MapColor.Brightness.LOW : MapColor.Brightness.LOWEST;
        return mapColour.calculateARGBColor(brightness) | 0xFF000000;
    }

    /** Something you can stand in: air, a torch, grass. Water and lava are floors. */
    private static boolean open(BlockState state) {
        return state.isAir() || (state.getFluidState().isEmpty() && !state.canOcclude());
    }

    /** Lighter for {@code amount} above zero, darker below, within reason. */
    static int tint(int argb, float amount) {
        float factor = Math.max(0.55f, Math.min(1.35f, 1f + amount));
        int r = Math.min(255, Math.round((argb >> 16 & 0xFF) * factor));
        int g = Math.min(255, Math.round((argb >> 8 & 0xFF) * factor));
        int b = Math.min(255, Math.round((argb & 0xFF) * factor));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
