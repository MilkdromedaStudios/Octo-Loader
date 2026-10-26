package net.minecraft.octotest;

/**
 * Stands in for a game or loader class whose members moved between static and
 * instance, as Forge 66's {@code LoadingModList.getModFileById} did.
 */
public final class Registry3 {
    private static final Registry3 INSTANCE = new Registry3();

    public static Registry3 get() {
        return INSTANCE;
    }

    /** An instance method now; older callers call it statically. */
    public String lookup(String key, int times) {
        return key.repeat(times);
    }

    /** Static now; older callers call it on an instance. */
    public static String describe(String key, long id) {
        return key + "#" + id;
    }
}
