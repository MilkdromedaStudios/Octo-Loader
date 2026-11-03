package studios.milkdromeda.octo.client.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.world.phys.Vec3;

/** Waypoints kept on disk, put on screen, and the map's height shading. */
class MapTest {
    @TempDir
    Path folder;

    @Test
    void waypointsSurviveASaveAndLoad() throws IOException {
        Path file = folder.resolve("waypoints").resolve("world-test.json");
        Waypoints.Waypoint home = new Waypoints.Waypoint("Home", 10, 64, -20, Waypoints.COLOURS[0],
                "minecraft:overworld");
        Waypoints.Waypoint death = new Waypoints.Waypoint("Death", 1, 2, 3, Waypoints.DEATH_COLOUR,
                "minecraft:the_nether");
        death.death = true;
        death.visible = false;
        Waypoints.save(file, List.of(home, death));

        List<Waypoints.Waypoint> read = Waypoints.load(file);
        assertEquals(2, read.size());
        assertEquals("Home", read.get(0).name);
        assertEquals(-20, read.get(0).z);
        assertTrue(read.get(1).death);
        assertTrue(!read.get(1).visible);
        assertEquals("minecraft:the_nether", read.get(1).dimension);

        // A damaged file costs the waypoints, not the game.
        Files.writeString(file, "{ not json");
        assertEquals(List.of(), Waypoints.load(file).stream().map(w -> w.name).toList());
        assertEquals(0, Waypoints.load(folder.resolve("missing.json")).size());
    }

    @Test
    void worldNamesMakeSafeFileNames() {
        assertEquals("my_world_", Waypoints.safe("My World!"));
        assertEquals("play.example.com_25565", Waypoints.safe("play.example.com:25565"));
        assertEquals("unnamed", Waypoints.safe(""));
        assertEquals(".._.._etc", Waypoints.safe("../../etc"));
    }

    @Test
    void markersLandWhereTheWaypointIs() {
        Vec3 eye = new Vec3(0, 64, 0);
        int w = 400;
        int h = 300;

        // Looking south (yaw 0), level: a point straight ahead is in the middle.
        WaypointMarkers.OnScreen ahead = WaypointMarkers.project(eye, 0, 0, 70, w, h, 0, 64, 10);
        assertEquals(200, ahead.x(), 0.01);
        assertEquals(150, ahead.y(), 0.01);
        assertEquals(10, ahead.distance(), 0.001);

        // Facing south, west (-x) is on the right, and up is up.
        WaypointMarkers.OnScreen west = WaypointMarkers.project(eye, 0, 0, 70, w, h, -3, 64, 10);
        assertTrue(west.x() > 200, "right of centre");
        WaypointMarkers.OnScreen above = WaypointMarkers.project(eye, 0, 0, 70, w, h, 0, 70, 10);
        assertTrue(above.y() < 150, "above centre");

        // At the edge of a 70° view, a point 35° up sits on the top edge.
        double up = Math.tan(Math.toRadians(35)) * 10;
        assertEquals(0, WaypointMarkers.project(eye, 0, 0, 70, w, h, 0, 64 + up, 10).y(), 0.01);

        // Behind the camera: not drawn.
        assertNull(WaypointMarkers.project(eye, 0, 0, 70, w, h, 0, 64, -10));

        // Facing east (yaw -90), a point east is in the middle; looking down at it lifts it up the screen.
        WaypointMarkers.OnScreen east = WaypointMarkers.project(eye, -90, 0, 70, w, h, 10, 64, 0);
        assertEquals(200, east.x(), 0.01);
        assertTrue(WaypointMarkers.project(eye, -90, 30, 70, w, h, 10, 64, 0).y() < 150);

        // Zooming (a narrower view) spreads things out from the middle.
        float wide = WaypointMarkers.project(eye, 0, 0, 70, w, h, -3, 64, 10).x();
        float narrow = WaypointMarkers.project(eye, 0, 0, 20, w, h, -3, 64, 10).x();
        assertTrue(narrow > wide);
    }

    @Test
    void distancesReadNaturally() {
        assertEquals("12 m", WaypointMarkers.distance(12.4));
        assertEquals("12.3 km", WaypointMarkers.distance(12_345));
    }

    @Test
    void mountainsBrighterValleysDarker() {
        int grey = 0xFF808080;
        assertEquals(grey, Terrain.tint(grey, 0));
        assertTrue((Terrain.tint(grey, 0.3f) & 0xFF) > 0x80, "higher is lighter");
        assertTrue((Terrain.tint(grey, -0.3f) & 0xFF) < 0x80, "lower is darker");
        // Within reason at the extremes, and never transparent.
        assertEquals(Math.round(0x80 * 0.55f), Terrain.tint(grey, -10) & 0xFF);
        assertEquals(0xFF, Terrain.tint(0xFFFFFFFF, 10) & 0xFF);
        assertEquals(0xFF000000, Terrain.tint(0x00808080, 0) & 0xFF000000);
    }
}
