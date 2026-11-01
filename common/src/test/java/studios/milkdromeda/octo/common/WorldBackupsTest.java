package studios.milkdromeda.octo.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldBackupsTest {
    @TempDir
    Path game;

    private Path world(String name) throws Exception {
        Path world = game.resolve("saves").resolve(name);
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(world.resolve("region/r.0.0.mca"), "chunks");
        Files.writeString(world.resolve("session.lock"), "lock");
        return world;
    }

    @Test
    void backsUpBesideTheSavesFolderAndRestores() throws Exception {
        Path world = world("My_World");
        WorldBackups.Backup backup = WorldBackups.create(world, WorldBackups.MANUAL);

        assertEquals(game.resolve("backups/My_World").toAbsolutePath().normalize(), backup.file().getParent());
        assertEquals("My_World", backup.world());
        assertEquals(WorldBackups.MANUAL, backup.reason());

        Files.writeString(world.resolve("level.dat"), "changed");
        Files.writeString(world.resolve("region/r.1.0.mca"), "new");
        Files.delete(world.resolve("session.lock"));

        WorldBackups.Backup before = WorldBackups.restore(backup, world);

        assertNotNull(before, "the world it replaced is backed up first");
        assertEquals("level", Files.readString(world.resolve("level.dat")));
        assertFalse(Files.exists(world.resolve("region/r.1.0.mca")));
        assertFalse(Files.exists(world.resolve("session.lock")), "the lock is never backed up");
        assertEquals(2, WorldBackups.list(WorldBackups.folderFor(world)).size());
    }

    @Test
    void prunesOnlyAutomaticBackups() throws Exception {
        Path world = world("w");
        Path folder = WorldBackups.folderFor(world);
        WorldBackups.create(world, WorldBackups.MANUAL);

        for (int i = 0; i < 4; i++) {
            WorldBackups.create(world, WorldBackups.MODS_CHANGED);
        }

        WorldBackups.prune(folder, 2);
        List<WorldBackups.Backup> left = WorldBackups.list(folder);

        assertEquals(3, left.size());
        assertEquals(1, left.stream().filter(backup -> !backup.isAutomatic()).count());
    }

    @Test
    void aServerWorldsBackupsSitBesideIt() {
        Path world = game.resolve("server/world");
        assertEquals(game.resolve("server/backups/world").toAbsolutePath().normalize(),
                WorldBackups.folderFor(world));
    }

    @Test
    void missingContentIsFoundAndCarriedOver() throws Exception {
        WorldContent before = new WorldContent(Map.of("create", "6.0.10", "deco", "2"),
                Map.of("block", List.of("create:cogwheel", "deco:brick"), "item", List.of("create:wrench")));
        WorldContent now = new WorldContent(Map.of("deco", "3"), Map.of("block", List.of("deco:brick")));

        WorldContent.Difference difference = before.compareTo(now, Set.of());

        assertTrue(difference.contentMissing());
        assertEquals(Set.of("create"), difference.missingMods());
        assertEquals(Set.of("create"), difference.removed());
        assertEquals(1, difference.updated().size());

        WorldContent carried = now.carryingOver(difference);
        assertTrue(carried.ids().get("block").contains("create:cogwheel"),
                "still missing, so still recorded: the world keeps needing it");

        Path world = world("w2");
        carried.write(world);
        WorldContent read = WorldContent.read(world);
        assertEquals(carried.ids(), read.ids());

        WorldContent.accept(world, List.of("create"));
        assertFalse(before.compareTo(now, WorldContent.accepted(world)).contentMissing());
        assertTrue(Files.readString(world.resolve(WorldContent.ACCEPTED_FILE), StandardCharsets.UTF_8)
                .contains("create"));
    }
}
