package studios.milkdromeda.octo.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import studios.milkdromeda.octo.launch.LaunchContext;
import studios.milkdromeda.octo.mod.ModCandidate;
import studios.milkdromeda.octo.mod.ModDependency;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.ModMetadata;
import studios.milkdromeda.octo.mod.version.Version;

/** What Octo claims to be when a 26.x mod asks for a loader by name. */
class PlatformVersionsTest {
    private static ModCandidate mod(String id, ModDependency... dependencies) {
        ModMetadata.Builder builder = ModMetadata.builder(id, ModFormat.FABRIC).version("1.0.0");

        for (ModDependency dependency : dependencies) {
            builder.dependency(dependency);
        }

        return new ModCandidate(Path.of(id + ".jar"), builder.build(), null);
    }

    private static Resolution resolve(List<ModCandidate> candidates) {
        LaunchContext context = LaunchContext.builder(Path.of("game")).minecraftVersion("26.3").build();
        return new ModResolver(context).resolve(candidates);
    }

    @Test
    @DisplayName("Forge and NeoForge are reported at the versions they shipped for 26.x")
    void loaderVersionsForYearReleases() {
        assertEquals(Version.parse("66.0.0"), ModResolver.forgeVersionFor(Version.parse("26.3")));
        assertEquals(Version.parse("62.0.0"), ModResolver.forgeVersionFor(Version.parse("26.1")));
        assertEquals(Version.parse("47.0.0"), ModResolver.forgeVersionFor(Version.parse("1.20.1")));
        assertEquals(Version.parse("26.3.0.0"), ModResolver.neoForgeVersionFor(Version.parse("26.3")));
        assertEquals(Version.parse("21.1.0"), ModResolver.neoForgeVersionFor(Version.parse("1.21.1")));
    }

    @Test
    @DisplayName("a game newer than the table counts on from the newest entry")
    void futureForgeVersions() {
        assertEquals(Version.parse("67.0.0"), ModResolver.forgeVersionFor(Version.parse("26.4")));
    }

    @Test
    @DisplayName("a mod requiring mixinextras gets the loader's copy, as Lithium for 26.3 does")
    void mixinExtrasIsProvided() {
        Resolution resolution = resolve(List.of(mod("lithium", ModDependency.required("mixinextras", ">=0.5.5"),
                ModDependency.required("fabricloader", ">=0.19.5"))));

        assertEquals(1, resolution.order().size(), "lithium should load: " + resolution.problems());
        assertTrue(resolution.fatalProblems().isEmpty());
    }

    @Test
    @DisplayName("an old MixinExtras a mod bundles cannot stand in for the loader's")
    void bundledBuiltinsAreIgnored() {
        Resolution resolution = resolve(List.of(mod("mixinextras"), mod("user",
                ModDependency.required("mixinextras", ">=0.5.5"))));

        assertFalse(resolution.order().stream().anyMatch(candidate -> candidate.id().equals("mixinextras")));
        assertTrue(resolution.order().stream().anyMatch(candidate -> candidate.id().equals("user")));
    }

    @Test
    @DisplayName("a mod asking for an id another mod provides finds it, as mods asking for cloth-config2 do")
    void providedIdsSatisfyDependencies() {
        ModCandidate cloth = new ModCandidate(Path.of("cloth.jar"), ModMetadata.builder("cloth-config", ModFormat.FABRIC)
                .version("21.0.0").provide("cloth-config2").build(), null);
        Resolution resolution = resolve(List.of(cloth, mod("user", ModDependency.required("cloth-config2", ">=20"))));

        assertTrue(resolution.order().stream().anyMatch(candidate -> candidate.id().equals("user")),
                "user should load: " + resolution.problems());
    }
}
