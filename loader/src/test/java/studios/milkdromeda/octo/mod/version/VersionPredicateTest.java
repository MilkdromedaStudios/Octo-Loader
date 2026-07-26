package studios.milkdromeda.octo.mod.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

/**
 * Version handling has to accept both dialects and everything fifteen years of
 * modders have written in them, because a mod that cannot be version-checked is
 * a mod that cannot be loaded.
 */
class VersionPredicateTest {
    @Nested
    @DisplayName("Fabric and Quilt syntax")
    class FabricSyntax {
        @Test
        void wildcardAcceptsAnything() {
            assertTrue(VersionPredicate.parse("*").test("1.20.1"));
            assertTrue(VersionPredicate.parse("*").isAny());
        }

        @Test
        void comparisons() {
            assertTrue(VersionPredicate.parse(">=1.20").test("1.20.1"));
            assertFalse(VersionPredicate.parse(">=1.20").test("1.19.4"));
            assertTrue(VersionPredicate.parse("<1.21").test("1.20.6"));
            assertFalse(VersionPredicate.parse("<1.21").test("1.21"));
        }

        @Test
        void conjunctionAndAlternation() {
            VersionPredicate range = VersionPredicate.parse(">=1.20 <1.21");
            assertTrue(range.test("1.20.4"));
            assertFalse(range.test("1.21"));

            VersionPredicate either = VersionPredicate.parse("1.19.2 || 1.20.1");
            assertTrue(either.test("1.19.2"));
            assertTrue(either.test("1.20.1"));
            assertFalse(either.test("1.18"));
        }

        @Test
        void tildeAndCaret() {
            assertTrue(VersionPredicate.parse("~1.20.1").test("1.20.4"));
            assertFalse(VersionPredicate.parse("~1.20.1").test("1.21.0"));
            assertTrue(VersionPredicate.parse("^1.20.1").test("1.99.0"));
            assertFalse(VersionPredicate.parse("^1.20.1").test("2.0.0"));
        }

        @Test
        void wildcardSuffix() {
            assertTrue(VersionPredicate.parse("1.20.x").test("1.20.6"));
            assertFalse(VersionPredicate.parse("1.20.x").test("1.19.2"));
        }
    }

    @Nested
    @DisplayName("Forge and NeoForge Maven ranges")
    class MavenSyntax {
        @Test
        void closedRange() {
            VersionPredicate range = VersionPredicate.parse("[1.12,1.13)");
            assertTrue(range.test("1.12.2"));
            assertFalse(range.test("1.13"));
            assertFalse(range.test("1.11.2"));
        }

        @Test
        void openUpperBound() {
            assertTrue(VersionPredicate.parse("[1.19,)").test("1.21.4"));
            assertFalse(VersionPredicate.parse("[1.19,)").test("1.18.2"));
        }

        @Test
        void openLowerBound() {
            assertTrue(VersionPredicate.parse("(,1.16.5]").test("1.12.2"));
            assertFalse(VersionPredicate.parse("(,1.16.5]").test("1.17"));
        }

        @Test
        void pinnedVersion() {
            assertTrue(VersionPredicate.parse("[1.7.10]").test("1.7.10"));
            assertFalse(VersionPredicate.parse("[1.7.10]").test("1.7.2"));
        }

        @Test
        @DisplayName("a bare version is a soft requirement, as Maven and Forge treat it")
        void bareVersionIsSoft() {
            assertTrue(VersionPredicate.parseRange("36.2.0").test("47.1.3"));
            // The same string in a Fabric dependency means exactly that version.
            assertFalse(VersionPredicate.parse("36.2.0").test("47.1.3"));
        }

        @Test
        @DisplayName("a versionRange written with comparison operators is still understood")
        void rangeWithOperators() {
            assertTrue(VersionPredicate.parseRange(">=1.19").test("1.20.1"));
            assertFalse(VersionPredicate.parseRange(">=1.19").test("1.18"));
        }
    }

    @Nested
    @DisplayName("versions modders actually wrote")
    class RealWorldVersions {
        @Test
        void compoundForgeVersion() {
            Version version = Version.parse("1.7.10-10.13.4.1614-1.7.10");
            assertEquals(1, version.part(0));
            assertEquals(7, version.part(1));
            assertTrue(VersionPredicate.parse("[1.7,1.8)").test(version));
        }

        @Test
        void betaVersionsSortBeforeRelease() {
            assertTrue(Version.parse("b1.7.3").compareTo(Version.parse("1.0")) < 0);
        }

        @Test
        void preReleaseSortsBeforeRelease() {
            assertTrue(Version.parse("1.0.0-beta.3").compareTo(Version.parse("1.0.0")) < 0);
            assertTrue(Version.parse("2.0.0-rc1").compareTo(Version.parse("2.0.0-alpha")) > 0);
        }

        @Test
        void buildMetadataIsIgnored() {
            assertEquals(0, Version.parse("4.2.1+1.20.1").compareTo(Version.parse("4.2.1+1.19.2")));
        }

        @Test
        void missingComponentsAreZero() {
            assertEquals(0, Version.parse("1.20").compareTo(Version.parse("1.20.0")));
        }

        @Test
        @DisplayName("an unparseable constraint admits everything rather than locking the mod out")
        void nonsenseIsPermissive() {
            assertTrue(VersionPredicate.parse("[[[nonsense").test("1.20.1"));
        }
    }
}
