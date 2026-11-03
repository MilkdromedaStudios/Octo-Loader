package studios.milkdromeda.octo.client.map;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * The colours of every chunk seen this session, for the big map: the chunks
 * around the player are coloured a few a tick (many more while the big map is
 * open) and kept after the player moves on, so the map shows where they have
 * been, not only what is loaded.
 *
 * <p>Kept for one view at a time: a dimension, a layer and, for caves, the
 * height they are seen from. Changing any of them starts afresh.
 */
public final class MapCache {
    /** About 16 MB of colours at most, the oldest dropped first. */
    private static final int MOST_CHUNKS = 16_384;
    /** Chunks near the player are coloured again this often, to show what changed. */
    private static final long NEAR_REFRESH_MS = 5_000;
    private static final long FAR_REFRESH_MS = 30_000;

    private record Seen(int[] colours, long made) {
    }

    private static final Map<Long, Seen> CHUNKS = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Seen> eldest) {
            return size() > MOST_CHUNKS;
        }
    };

    private static String view;
    private static int referenceY;
    private static Terrain.Layer layer = Terrain.Layer.SURFACE;
    private static long lastChunk = Long.MIN_VALUE;
    private static int[] lastColours;

    private MapCache() {
    }

    /** The layer the cached colours are of. */
    public static Terrain.Layer layer() {
        return layer;
    }

    /** One block's colour, or {@link Terrain#UNKNOWN} where nothing has been seen. */
    public static int colour(int x, int z) {
        long key = ChunkPos.pack(x >> 4, z >> 4);
        int[] colours;

        if (key == lastChunk) {
            colours = lastColours;
        } else {
            Seen entry = CHUNKS.get(key);
            colours = entry == null ? null : entry.colours();
            lastChunk = key;
            lastColours = colours;
        }

        return colours == null ? Terrain.UNKNOWN : colours[(z & 15) << 4 | (x & 15)];
    }

    /**
     * Colours up to {@code budget} chunks: those never seen first, then the
     * ones whose colours are oldest, nearest the player first.
     */
    public static void tick(Minecraft minecraft, int budget) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;

        if (player == null || level == null) {
            return;
        }

        Terrain.Layer nowLayer = Terrain.layer(minecraft);
        // Caves are seen from a height: a band of eight blocks, so climbing a ladder does not start over.
        int nowY = nowLayer == Terrain.Layer.CAVES ? (player.getBlockY() >> 3 << 3) + 4 : 0;
        String nowView = Waypoints.dimension(minecraft) + "/" + nowLayer + "/" + nowY;

        if (!nowView.equals(view)) {
            view = nowView;
            layer = nowLayer;
            referenceY = nowY;
            CHUNKS.clear();
            lastChunk = Long.MIN_VALUE;
            lastColours = null;
        }

        int radius = Math.max(2, minecraft.options.getEffectiveRenderDistance());
        int cx = player.getBlockX() >> 4;
        int cz = player.getBlockZ() >> 4;
        long now = System.currentTimeMillis();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int y = layer == Terrain.Layer.CAVES ? referenceY : player.getBlockY();

        // Ring by ring outwards, so the nearest come first.
        for (int ring = 0; ring <= radius && budget > 0; ring++) {
            for (int dx = -ring; dx <= ring && budget > 0; dx++) {
                for (int dz = -ring; dz <= ring && budget > 0; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || !level.hasChunk(cx + dx, cz + dz)) {
                        continue;
                    }

                    long key = ChunkPos.pack(cx + dx, cz + dz);
                    Seen entry = CHUNKS.get(key);
                    long due = ring <= 2 ? NEAR_REFRESH_MS : FAR_REFRESH_MS;

                    if (entry != null && now - entry.made() < due) {
                        continue;
                    }

                    CHUNKS.put(key, new Seen(chunk(level, pos, cx + dx, cz + dz, y), now));
                    budget--;

                    if (key == lastChunk) {
                        lastChunk = Long.MIN_VALUE;
                    }
                }
            }
        }
    }

    private static int[] chunk(ClientLevel level, BlockPos.MutableBlockPos pos, int chunkX, int chunkZ, int y) {
        int[] colours = new int[256];

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                colours[z << 4 | x] = Terrain.colour(level, pos, (chunkX << 4) + x, (chunkZ << 4) + z, layer, y);
            }
        }

        return colours;
    }
}
