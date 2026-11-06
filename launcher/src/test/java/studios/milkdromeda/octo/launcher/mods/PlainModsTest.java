package studios.milkdromeda.octo.launcher.mods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlainModsTest {
    private static Modrinth.Version version(String id, String game, String loader, String published) {
        return new Modrinth.Version(id, "p", id, id, List.of(game), List.of(loader), published, "", id + ".jar", "",
                0, List.of());
    }

    @Test
    void aPlainGameGetsItsOwnLoadersBuildForItsExactVersion() {
        List<Modrinth.Version> versions = List.of(
                version("fabric-old", "26.3", "fabric", "2026-01-01"),
                version("fabric-new", "26.3", "fabric", "2026-06-01"),
                version("neo", "26.3", "neoforge", "2026-09-01"),
                version("fabric-1211", "1.21.1", "fabric", "2026-10-01"));

        assertEquals("fabric-new", new ModLibrary(Path.of("."), "fabric").choose(versions, "26.3").id());
        assertEquals("neo", new ModLibrary(Path.of("."), "neoforge").choose(versions, "26.3").id());
        assertNull(new ModLibrary(Path.of("."), "quilt").choose(versions, "26.3"), "no Quilt build");
        assertNull(new ModLibrary(Path.of("."), "fabric").choose(versions, "26.2"), "no build for that version");
        assertNull(new ModLibrary(Path.of("."), "vanilla").choose(versions, "26.3"), "vanilla takes no mods");
    }
}
