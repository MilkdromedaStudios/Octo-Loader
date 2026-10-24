package studios.milkdromeda.octo.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import studios.milkdromeda.octo.compat.Era;
import studios.milkdromeda.octo.compat.Era.MappingNamespace;
import studios.milkdromeda.octo.compat.EraDetector;
import studios.milkdromeda.octo.mod.ModDependency;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.ModMetadata;

/**
 * Which game a mod was built for, now that 26.x exists.
 *
 * <p>The case that started this: Fabric API's modules for 26.3 declare
 * {@code "minecraft": ">=1.16.2"} — the version each module first appeared in —
 * and were translated as if they were built for 1.16.
 */
class EraEvidenceTest {
    private final EraDetector detector = new EraDetector();

    private static ScanResult scan(int javaVersion, MappingNamespace... hits) {
        ScanResult scan = new ScanResult();
        scan.countClass(javaVersion + 44);

        for (MappingNamespace hit : hits) {
            scan.hit(hit);
        }

        return scan;
    }

    private static ModMetadata fabric(String minecraft) {
        ModMetadata.Builder builder = ModMetadata.builder("example", ModFormat.FABRIC).version("1.0.0");

        if (minecraft != null) {
            builder.dependency(ModDependency.required("minecraft", minecraft));
        }

        return builder.build();
    }

    @Test
    @DisplayName("an open-ended range is a floor, not the version a mod was built for")
    void openEndedRangeIsAFloor() {
        Era era = detector.detect(fabric(">=1.16.2"), scan(25, MappingNamespace.MOJANG, MappingNamespace.MOJANG));
        assertEquals(Era.UNOBFUSCATED, era);
    }

    @Test
    @DisplayName("a closed range says what a mod was built for")
    void closedRangeIsTheTarget() {
        assertEquals(Era.CURRENT, detector.detect(fabric("~1.21"), scan(21, MappingNamespace.INTERMEDIARY)));
    }

    @Test
    @DisplayName("Fabric intermediary in the bytecode means a game from before 26.1, whatever else is claimed")
    void intermediaryCapsTheEra() {
        assertEquals(Era.CURRENT, detector.detect(fabric(">=1.20.3"), scan(21, MappingNamespace.INTERMEDIARY)));
    }

    @Test
    @DisplayName("Forge's SRG member names put a mod in 1.17 to 1.20.4")
    void srgIsModern() {
        ModMetadata forge = ModMetadata.builder("example", ModFormat.FORGE).version("1.0.0").build();
        ScanResult scan = scan(17, MappingNamespace.MOJANG, MappingNamespace.MOJANG, MappingNamespace.SRG);

        assertEquals(Era.MODERN, detector.detect(forge, scan));
        assertEquals(MappingNamespace.SRG, scan.bytecodeNamespace());
    }

    @Test
    @DisplayName("a 26.x version string is the unobfuscated era")
    void yearVersionsAreUnobfuscated() {
        assertEquals(Era.UNOBFUSCATED, Era.forMinecraftVersion("26.3"));
        assertEquals(Era.UNOBFUSCATED, Era.forMinecraftVersion("26.4-snapshot-5"));
        assertEquals(Era.UNOBFUSCATED, Era.forMinecraftVersion("26.3-rc-3"));
        assertEquals(Era.CURRENT, Era.forMinecraftVersion("1.21.11"));
    }
}
