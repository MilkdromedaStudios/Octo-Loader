package net.neoforged.neoforge.registries;

/**
 * Test-classpath-only stand-in for a NeoForge API Octo does NOT shim. Fixture
 * mods compiled in tests reference it to exercise the coverage index — it must
 * never move to the main source set.
 */
public final class DeferredRegister {
    private DeferredRegister() {
    }

    public static DeferredRegister create(String registry, String modId) {
        throw new UnsupportedOperationException("test stub");
    }
}
