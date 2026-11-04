package studios.milkdromeda.octo.discovery;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import studios.milkdromeda.octo.mod.ModCandidate;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.ModMetadata;

/** A mod packed whole inside a jar declaring the same mod, as NeoForge's Sodium is. */
class SameModInsideTest {
    private static ModCandidate mod(String file, String id, String version, ModCandidate parent) {
        return new ModCandidate(Path.of(file), ModMetadata.builder(id, ModFormat.NEOFORGE).version(version).build(),
                parent);
    }

    @Test
    @DisplayName("the jar inside declaring the same mod and version is the mod")
    void theInnerJarIsTheMod() {
        ModCandidate outer = mod("sodium-neoforge.jar", "sodium", "0.9.3", null);
        ModCandidate inner = mod("sodium-neoforge-mod.jar", "sodium", "0.9.3", outer);
        ModCandidate library = mod("library.jar", "something-else", "1.0", outer);

        assertSame(inner, ModDiscoverer.sameModInside(outer, List.of(library, inner)));
    }

    @Test
    @DisplayName("another mod inside, or another version of it, is left to be its own mod")
    void otherModsInsideAreTheirOwn() {
        ModCandidate outer = mod("create.jar", "create", "6.0.10", null);

        assertNull(ModDiscoverer.sameModInside(outer, List.of(mod("flywheel.jar", "flywheel", "1.0.6", outer))));
        assertNull(ModDiscoverer.sameModInside(outer, List.of(mod("create-old.jar", "create", "6.0.9", outer))));
    }
}
