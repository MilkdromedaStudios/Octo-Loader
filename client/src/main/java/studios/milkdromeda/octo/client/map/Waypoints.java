package studios.milkdromeda.octo.client.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.storage.LevelResource;

import studios.milkdromeda.octo.client.OctoClient;

/**
 * The player's waypoints, kept for each world and server on their own, in
 * {@code config/octo-client/waypoints}. Each belongs to a dimension, and the
 * maps and the world show only the ones for where the player is.
 */
public final class Waypoints {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The colours a waypoint can be, one after the other for new ones. */
    public static final int[] COLOURS = { 0xFF8B5CF6, 0xFF3B82F6, 0xFF06B6D4, 0xFF22C55E, 0xFFEAB308, 0xFFF97316,
            0xFFEF4444, 0xFFEC4899 };
    public static final int DEATH_COLOUR = 0xFFEF4444;

    /** One place to find again. Its fields are what is saved. */
    public static final class Waypoint {
        public String name;
        public int x;
        public int y;
        public int z;
        public int colour;
        /** The dimension's id, such as {@code minecraft:overworld}. */
        public String dimension;
        public boolean visible = true;
        /** Where the player last died: replaced each time. */
        public boolean death;

        public Waypoint() {
        }

        public Waypoint(String name, int x, int y, int z, int colour, String dimension) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.colour = colour;
            this.dimension = dimension;
        }

        public double distanceTo(double px, double py, double pz) {
            double dx = x + 0.5 - px;
            double dy = y + 0.5 - py;
            double dz = z + 0.5 - pz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private static String loadedKey;
    private static List<Waypoint> all = new ArrayList<>();
    private static boolean wasDead;

    private Waypoints() {
    }

    /** Which world or server this is, as a file name; {@code null} when there is none. */
    static String worldKey(Minecraft minecraft) {
        try {
            if (minecraft.getSingleplayerServer() != null) {
                Path root = minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath()
                        .normalize();
                return "world-" + safe(root.getFileName().toString());
            }

            if (minecraft.getCurrentServer() != null) {
                return "server-" + safe(minecraft.getCurrentServer().ip);
            }
        } catch (RuntimeException e) {
            // Between worlds.
        }

        return null;
    }

    static String safe(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
        return cleaned.isEmpty() ? "unnamed" : cleaned;
    }

    private static Path file(Minecraft minecraft, String key) {
        return minecraft.gameDirectory.toPath().resolve("config").resolve("octo-client").resolve("waypoints")
                .resolve(key + ".json");
    }

    /** Every waypoint of this world, loaded the first time it is asked for. */
    public static List<Waypoint> all(Minecraft minecraft) {
        String key = worldKey(minecraft);

        if (key == null) {
            loadedKey = null;
            all = new ArrayList<>();
            return all;
        }

        if (!key.equals(loadedKey)) {
            loadedKey = key;
            all = load(file(minecraft, key));
        }

        return all;
    }

    static List<Waypoint> load(Path file) {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }

        try {
            List<Waypoint> read = GSON.fromJson(Files.readString(file), new TypeToken<List<Waypoint>>() { }.getType());
            List<Waypoint> out = new ArrayList<>();

            if (read != null) {
                for (Waypoint waypoint : read) {
                    if (waypoint != null && waypoint.name != null && waypoint.dimension != null) {
                        out.add(waypoint);
                    }
                }
            }

            return out;
        } catch (IOException | RuntimeException e) {
            OctoClient.LOG.warn("could not read waypoints from {}: {}", file, e.toString());
            return new ArrayList<>();
        }
    }

    public static void save(Minecraft minecraft) {
        if (loadedKey == null) {
            return;
        }

        save(file(minecraft, loadedKey), all);
    }

    static void save(Path file, List<Waypoint> waypoints) {
        try {
            Files.createDirectories(file.getParent());
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.writeString(part, GSON.toJson(waypoints));
            Files.move(part, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            OctoClient.LOG.warn("could not save waypoints to {}: {}", file, e.toString());
        }
    }

    /** The waypoints of the dimension the player is in, shown or not. */
    public static List<Waypoint> here(Minecraft minecraft) {
        if (minecraft.level == null) {
            return List.of();
        }

        String dimension = dimension(minecraft);
        List<Waypoint> out = new ArrayList<>();

        for (Waypoint waypoint : all(minecraft)) {
            if (dimension.equals(waypoint.dimension)) {
                out.add(waypoint);
            }
        }

        return out;
    }

    public static String dimension(Minecraft minecraft) {
        return minecraft.level.dimension().identifier().toString();
    }

    /** A new waypoint, named and coloured after the ones before it. */
    public static Waypoint add(Minecraft minecraft, int x, int y, int z) {
        List<Waypoint> list = all(minecraft);
        int number = 1;

        for (Waypoint waypoint : list) {
            if (!waypoint.death) {
                number++;
            }
        }

        Waypoint waypoint = new Waypoint("Waypoint " + number, x, y, z, COLOURS[(number - 1) % COLOURS.length],
                dimension(minecraft));
        list.add(waypoint);
        save(minecraft);
        return waypoint;
    }

    public static void remove(Minecraft minecraft, Waypoint waypoint) {
        all(minecraft).remove(waypoint);
        save(minecraft);
    }

    /** Every tick: a waypoint where the player dies, in place of the last one. */
    public static void tick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;

        if (player == null || minecraft.level == null) {
            wasDead = false;
            return;
        }

        boolean dead = player.isDeadOrDying();

        if (dead && !wasDead && OctoClient.config().deathWaypoint) {
            List<Waypoint> list = all(minecraft);
            list.removeIf(waypoint -> waypoint.death);
            Waypoint death = new Waypoint("Death", player.getBlockX(), player.getBlockY(), player.getBlockZ(),
                    DEATH_COLOUR, dimension(minecraft));
            death.death = true;
            list.add(death);
            save(minecraft);
        }

        wasDead = dead;
    }
}
