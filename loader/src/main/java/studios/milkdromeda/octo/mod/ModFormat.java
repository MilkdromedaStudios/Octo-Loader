package studios.milkdromeda.octo.mod;

/**
 * The mod packaging formats Octo understands natively.
 *
 * <p>Every one of these was, at some point, "the" way to write a Minecraft mod.
 * A loader that only speaks the current one leaves fifteen years of work on the floor.
 */
public enum ModFormat {
    /** {@code fabric.mod.json} — Fabric, 1.14 onwards. */
    FABRIC("Fabric", Family.FABRIC, "fabric.mod.json"),

    /** {@code quilt.mod.json} — Quilt, 1.18 onwards. */
    QUILT("Quilt", Family.FABRIC, "quilt.mod.json"),

    /** {@code META-INF/mods.toml} — Forge, 1.13 onwards. */
    FORGE("Forge", Family.FORGE, "META-INF/mods.toml"),

    /** {@code META-INF/neoforge.mods.toml} — NeoForge, 1.20.5 onwards. */
    NEOFORGE("NeoForge", Family.FORGE, "META-INF/neoforge.mods.toml"),

    /** {@code mcmod.info} — Forge, 1.2.5 through 1.12.2. The forgotten years. */
    LEGACY_FORGE("Forge (legacy)", Family.FORGE, "mcmod.info"),

    /** {@code litemod.json} — LiteLoader, 1.5.2 through 1.12.2. */
    LITELOADER("LiteLoader", Family.LITELOADER, "litemod.json"),

    /** {@code riftmod.json} — Rift, the 1.13 stopgap. */
    RIFT("Rift", Family.FABRIC, "riftmod.json"),

    /**
     * No metadata file survived, but the jar carries a recognisable entrypoint —
     * an {@code @Mod} annotation, a {@code ModInitializer}, or a {@code mod_*} class
     * from the ModLoader era. Recovered by bytecode scan.
     */
    BARE("Bare jar", Family.FORGE, null);

    /**
     * Which loader lineage a format belongs to. Formats in the same family share
     * an entrypoint contract, so a bridge written for one usually serves the other.
     */
    public enum Family { FABRIC, FORGE, LITELOADER }

    private final String displayName;
    private final Family family;
    private final String metadataPath;

    ModFormat(String displayName, Family family, String metadataPath) {
        this.displayName = displayName;
        this.family = family;
        this.metadataPath = metadataPath;
    }

    public String displayName() {
        return displayName;
    }

    public Family family() {
        return family;
    }

    /** The jar entry that identifies this format, or {@code null} for {@link #BARE}. */
    public String metadataPath() {
        return metadataPath;
    }

    public boolean isLegacy() {
        return this == LEGACY_FORGE || this == LITELOADER || this == RIFT || this == BARE;
    }
}
