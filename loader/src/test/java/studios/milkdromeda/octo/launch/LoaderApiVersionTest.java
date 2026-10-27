package studios.milkdromeda.octo.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which NeoForge or Forge build Octo takes its loader API from. */
class LoaderApiVersionTest {
    @Test
    @DisplayName("the newest stable build is taken, though betas numbered higher exist")
    void stableBeatsBeta() {
        List<String> versions = List.of("26.2.0.40", "26.3.0.0-beta", "26.3.0.75-beta", "26.3.0.97", "26.3.0.99",
                "26.3.0.120-beta", "26.4.0.0-alpha.1+snapshot-2");

        assertEquals(Optional.of("26.3.0.99"), LoaderApiLibraries.pick(versions, "26.3."));
    }

    @Test
    @DisplayName("a beta is taken only while nothing stable exists for that Minecraft")
    void betaWhenNothingStable() {
        assertEquals(Optional.of("26.3.0.75-beta"),
                LoaderApiLibraries.pick(List.of("26.3.0.8-beta", "26.3.0.75-beta"), "26.3."));
        assertEquals(Optional.empty(), LoaderApiLibraries.pick(List.of("26.2.0.40"), "26.3."));
    }

    @Test
    @DisplayName("pre-releases are told apart from releases")
    void preReleases() {
        assertTrue(LoaderApiLibraries.isPreRelease("26.3.0.75-beta"));
        assertTrue(LoaderApiLibraries.isPreRelease("26.4.0.0-alpha.1+snapshot-2"));
        assertFalse(LoaderApiLibraries.isPreRelease("26.3.0.99"));
        assertFalse(LoaderApiLibraries.isPreRelease("26.3-66.1.0"), "Forge's own version scheme is a release");
    }
}
