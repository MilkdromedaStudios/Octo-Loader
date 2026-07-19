package studios.milkdromeda.octoloader.report;

/**
 * Final compatibility verdict for a jar found in the mods directory.
 */
public enum CompatStatus {
    /** Native Fabric mod — Fabric Loader handles it directly. */
    NATIVE("native"),
    /** Successfully translated into a Fabric mod and handed to the loader. */
    TRANSLATED("translated"),
    /** Translated but references APIs Octo Loader does not provide yet — skipped. */
    PARTIAL("partial"),
    /** Built for a different Minecraft version — cannot run on this one. */
    UNSUPPORTED_VERSION("unsupported version"),
    /** Ecosystem recognized but not yet translatable (e.g. Paper plugins). */
    UNSUPPORTED_ECOSYSTEM("unsupported ecosystem"),
    /** Metadata unreadable or no ecosystem marker found. */
    UNRECOGNIZED("unrecognized"),
    /** Translation was attempted but failed unexpectedly. */
    ERROR("error");

    private final String label;

    CompatStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
