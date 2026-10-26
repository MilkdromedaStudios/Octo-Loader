package studios.milkdromeda.octo.rebuild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import studios.milkdromeda.octo.mod.ModCandidate;
import studios.milkdromeda.octo.mod.ModDependency;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.ModMetadata;
import studios.milkdromeda.octo.runtime.LoadedMod;

/**
 * Which Minecraft a mod was built for, when its metadata never says.
 *
 * <p>Jade, AppleSkin and Mouse Tweaks for NeoForge 1.21.1 name no Minecraft
 * range at all. Without a release there is no table to rename them by and no
 * old jar to add classes back from, so they ran untranslated.
 */
class ReleaseDetectionTest {
    private static LoadedMod mod(String fileName, ModFormat format, ModDependency... dependencies) {
        ModMetadata.Builder metadata = ModMetadata.builder("example", format).version("1.0.0");

        for (ModDependency dependency : dependencies) {
            metadata.dependency(dependency);
        }

        return new LoadedMod(new ModCandidate(Path.of("mods", fileName), metadata.build(), null));
    }

    @Test
    @DisplayName("a NeoForge range gives the release: 21.0 is 1.21, 21.1 is 1.21.1, 20.4 is 1.20.4")
    void neoForgeRangeGivesTheRelease() {
        assertEquals("1.21", ModRebuilder.builtFor(mod("jade.jar", ModFormat.NEOFORGE,
                // What loaderVersion="[1,)" becomes: FML's language loader, not NeoForge.
                ModDependency.required("neoforge", "[1,)"),
                ModDependency.required("neoforge", "[21.0.143,)"))));
        assertEquals("1.21.1", ModRebuilder.builtFor(mod("x.jar", ModFormat.NEOFORGE,
                ModDependency.required("neoforge", "[21.1.77,)"))));
        assertEquals("1.20.4", ModRebuilder.builtFor(mod("x.jar", ModFormat.NEOFORGE,
                ModDependency.required("neoforge", "[20.4.80,)"))));
    }

    @Test
    @DisplayName("a Forge range gives the release through Forge's version table")
    void forgeRangeGivesTheRelease() {
        assertEquals("1.20.1", ModRebuilder.builtFor(mod("appleskin.jar", ModFormat.FORGE,
                ModDependency.required("forge", "[47.0.12,)"))));
        assertEquals("1.12.2", ModRebuilder.builtFor(mod("x.jar", ModFormat.FORGE,
                ModDependency.required("forge", "[14.23.5.2847,)"))));
    }

    @Test
    @DisplayName("with no range at all, the jar's name gives the release")
    void fileNameGivesTheRelease() {
        assertEquals("1.21", ModRebuilder.builtFor(mod("MouseTweaks-neoforge-mc1.21-2.26.1.jar", ModFormat.NEOFORGE)));
        assertEquals("1.20.1", ModRebuilder.builtFor(mod("Jade-1.20.1-Forge-11.13.3.jar", ModFormat.FORGE)));
        assertNull(ModRebuilder.fromFileName("somemod-2.26.1.jar"), "a mod's own version is not a game version");
    }

    @Test
    @DisplayName("a declared Minecraft range still comes first")
    void minecraftRangeComesFirst() {
        assertEquals("1.21.1", ModRebuilder.builtFor(mod("thing-mc1.20.jar", ModFormat.NEOFORGE,
                ModDependency.required("minecraft", "[1.21.1,1.21.2)"),
                ModDependency.required("neoforge", "[21.0.0,)"))));
    }
}
