package studios.milkdromeda.octo.compat;

import studios.milkdromeda.octo.mod.version.Version;

/**
 * A generation of Minecraft modding.
 *
 * <p>Each era had its own naming scheme for the game's own classes, its own
 * loader, and its own idea of what a mod looked like. Knowing which era a jar
 * came from is what lets Octo decide how to translate it forward.
 */
public enum Era {
    UNKNOWN("unknown", null, null, MappingNamespace.UNKNOWN),

    /** Classic and Indev. Single-package obfuscated game, mods were patched class files. */
    CLASSIC("classic", null, "1.0.0", MappingNamespace.OFFICIAL),

    /** Alpha 1.x — ModLoader's first home. */
    ALPHA("alpha", "a1.0.0", "a1.2.6", MappingNamespace.OFFICIAL),

    /** Beta 1.x — ModLoader and the first Forge. */
    BETA("beta", "b1.0", "b1.9", MappingNamespace.OFFICIAL),

    /** 1.0 – 1.2.5. {@code mod_*} classes and {@code mcmod.info} arrive. */
    MODLOADER("modloader", "1.0", "1.2.5", MappingNamespace.OFFICIAL),

    /** 1.3 – 1.6.4. FML merges into Forge; the client/server jars merge too. */
    FML("fml", "1.3", "1.6.4", MappingNamespace.OFFICIAL),

    /** 1.7 – 1.12.2. The Searge years: {@code func_71410_x}, {@code field_71439_g}. */
    SEARGE("searge", "1.7", "1.12.2", MappingNamespace.SEARGE),

    /** 1.13 – 1.16.5. The Flattening, and Fabric's intermediary namespace. */
    FLATTENING("flattening", "1.13", "1.16.5", MappingNamespace.INTERMEDIARY),

    /** 1.17 – 1.20.4. Java 16+, modules, official Mojang mappings in common use. */
    MODERN("modern", "1.17", "1.20.4", MappingNamespace.MOJANG),

    /** 1.20.5 and later. NeoForge's own metadata file, component-based items. */
    CURRENT("current", "1.20.5", null, MappingNamespace.MOJANG);

    /** The naming scheme the game's classes carried in a given era. */
    public enum MappingNamespace {
        UNKNOWN,
        /** Mojang's obfuscated names: {@code bao}, {@code a(I)V}. */
        OFFICIAL,
        /** MCP/Searge's stable numbered names: {@code func_71410_x}. */
        SEARGE,
        /** Fabric's stable numbered names: {@code class_310}, {@code method_1551}. */
        INTERMEDIARY,
        /** Mojang's published deobfuscation maps: {@code Minecraft.getInstance}. */
        MOJANG
    }

    private final String id;
    private final String first;
    private final String last;
    private final MappingNamespace namespace;

    Era(String id, String first, String last, MappingNamespace namespace) {
        this.id = id;
        this.first = first;
        this.last = last;
        this.namespace = namespace;
    }

    public String id() {
        return id;
    }

    public MappingNamespace namespace() {
        return namespace;
    }

    public boolean isKnown() {
        return this != UNKNOWN;
    }

    /** {@code true} when this era predates the era Octo is currently running under. */
    public boolean isOlderThan(Era other) {
        return ordinal() < other.ordinal();
    }

    public boolean contains(Version version) {
        if (first != null && version.compareTo(Version.parse(first)) < 0) {
            return false;
        }

        return last == null || version.compareTo(Version.parse(last)) <= 0;
    }

    /** Maps a Minecraft version string onto its era. */
    public static Era forMinecraftVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }

        String text = raw.trim().toLowerCase(java.util.Locale.ROOT);

        // Snapshots (24w14a, 1.21-pre1) are treated as the era of their release line.
        if (text.matches("\\d{2}w\\d{2}[a-z]")) {
            int year = Integer.parseInt(text.substring(0, 2));
            return year >= 24 ? CURRENT : year >= 21 ? MODERN : year >= 19 ? FLATTENING : SEARGE;
        }

        if (text.startsWith("a")) {
            return ALPHA;
        }

        if (text.startsWith("b")) {
            return BETA;
        }

        if (text.startsWith("c") || text.startsWith("in") || text.startsWith("rd")) {
            return CLASSIC;
        }

        Version version = Version.parse(text);

        for (Era era : values()) {
            if (era == UNKNOWN || era.first == null && era.last == null) {
                continue;
            }

            // The pre-release eras are matched by prefix above.
            if (era == ALPHA || era == BETA || era == CLASSIC) {
                continue;
            }

            if (era.contains(version)) {
                return era;
            }
        }

        return version.part(0) >= 1 && version.part(1) > 20 ? CURRENT : UNKNOWN;
    }

    @Override
    public String toString() {
        return id;
    }
}
