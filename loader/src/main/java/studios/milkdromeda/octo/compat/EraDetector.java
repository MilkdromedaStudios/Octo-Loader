package studios.milkdromeda.octo.compat;

import studios.milkdromeda.octo.compat.Era.MappingNamespace;
import studios.milkdromeda.octo.discovery.ScanResult;
import studios.milkdromeda.octo.mod.ModDependency;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.ModMetadata;

/**
 * Works out which generation of Minecraft a mod was built for.
 *
 * <p>Old jars are unreliable narrators: the metadata may say nothing, or say
 * something wrong, or say {@code "mcversion": "[1.7.10]"} in a field the loader
 * of the day ignored. So four independent signals are weighed — what the author
 * declared, which naming scheme the bytecode uses, which Java version it was
 * compiled for, and what the packaging format implies — and the strongest wins.
 */
public final class EraDetector {
    /**
     * @param metadata the mod's declared metadata
     * @param scan     the result of reading its bytecode, or {@code null} if not scanned
     */
    public Era detect(ModMetadata metadata, ScanResult scan) {
        // What the bytecode proves. The naming scheme is the hardest evidence:
        // Fabric stopped compiling against intermediary when the game stopped
        // being obfuscated, so a mod full of class_310 predates 26.1, and a
        // Fabric mod speaking Mojang's names cannot. A class file version is a
        // floor too — Java 25 classes cannot have been built for a game that ran
        // on Java 21 — though a weaker one, since a mod can be recompiled.
        Era namedFloor = scan == null ? Era.UNKNOWN : floorFromNamespace(metadata.format(), scan);
        Era ceiling = scan == null ? Era.UNKNOWN : ceilingFromNamespace(scan);
        Era classFloor = scan == null ? Era.UNKNOWN : floorFromJavaVersion(scan.javaVersion());

        // The author's own statement of the exact version comes next, and
        // outranks the class file version: a 1.7.10 mod recompiled on a modern
        // JDK is still a 1.7.10 mod.
        Era declared = fromDeclaration(metadata);

        if (declared.isKnown()) {
            return clamp(declared, namedFloor, ceiling);
        }

        // An open-ended range only says how old a mod may be, not what it was
        // built for: ">=1.16.2" on a Fabric API module for 26.3 is the version
        // that module first appeared in.
        Era floor = Era.latest(Era.latest(namedFloor, classFloor), floorFromDeclaration(metadata));

        Era fromBytecode = scan == null ? Era.UNKNOWN : fromNamespace(scan);

        if (fromBytecode.isKnown()) {
            return clamp(fromBytecode, floor, ceiling);
        }

        Era fromFormat = fromFormat(metadata.format());

        if (fromFormat.isKnown()) {
            return clamp(fromFormat, floor, ceiling);
        }

        if (floor.isKnown()) {
            return clamp(floor, floor, ceiling);
        }

        return scan == null ? Era.UNKNOWN : clamp(fromJavaVersion(scan.javaVersion()), floor, ceiling);
    }

    private static Era clamp(Era era, Era floor, Era ceiling) {
        Era out = Era.latest(era, floor);

        if (ceiling.isKnown() && floor.isKnown() && ceiling.isOlderThan(floor)) {
            // The evidence disagrees with itself — a Java 25 jar full of
            // intermediary names. The class file version is the harder fact.
            return out;
        }

        return ceiling.isKnown() && ceiling.isOlderThan(out) ? ceiling : out;
    }

    /**
     * The author's own statement of what the mod was built for: a legacy
     * {@code mcversion}, or a Minecraft range closed at the top.
     */
    private Era fromDeclaration(ModMetadata metadata) {
        String declared = metadata.declaredMinecraftVersion().orElse(null);

        if (declared != null) {
            Era era = Era.forMinecraftVersion(stripRange(declared));

            if (era.isKnown()) {
                return era;
            }
        }

        ModDependency minecraft = metadata.minecraftDependency().orElse(null);

        if (minecraft != null && !minecraft.versions().isAny() && minecraft.versions().isBoundedAbove()) {
            Era era = Era.forMinecraftVersion(minecraft.versions().lowerBound().raw());

            if (era.isKnown()) {
                return era;
            }
        }

        return Era.UNKNOWN;
    }

    /** The oldest game a mod with an open-ended range admits to. */
    private Era floorFromDeclaration(ModMetadata metadata) {
        ModDependency minecraft = metadata.minecraftDependency().orElse(null);

        if (minecraft == null || minecraft.versions().isAny()) {
            return Era.UNKNOWN;
        }

        return Era.forMinecraftVersion(minecraft.versions().lowerBound().raw());
    }

    /** The oldest era whose game could have run classes of this version. */
    private static Era floorFromJavaVersion(int javaVersion) {
        if (javaVersion >= 25) {
            return Era.UNOBFUSCATED;
        }

        if (javaVersion >= 21) {
            return Era.CURRENT;
        }

        if (javaVersion >= 16) {
            return Era.MODERN;
        }

        return Era.UNKNOWN;
    }

    /**
     * What the naming scheme says about how new a mod is.
     *
     * <p>Mojang's names in a Fabric mod's bytecode mean an unobfuscated game: a
     * Fabric mod for anything before 26.1 was remapped to intermediary when it
     * was built. NeoForge has used Mojang's names at runtime since 1.20.5.
     */
    private static Era floorFromNamespace(ModFormat format, ScanResult scan) {
        if (scan.bytecodeNamespace() != MappingNamespace.MOJANG) {
            return Era.UNKNOWN;
        }

        return switch (format) {
            case FABRIC, QUILT -> scan.namespaceHits(MappingNamespace.INTERMEDIARY) == 0
                    ? Era.UNOBFUSCATED : Era.UNKNOWN;
            case NEOFORGE -> Era.CURRENT;
            default -> Era.UNKNOWN;
        };
    }

    /** The newest era that still used a mod's naming scheme. */
    private static Era ceilingFromNamespace(ScanResult scan) {
        return switch (scan.bytecodeNamespace()) {
            case INTERMEDIARY -> Era.CURRENT;
            case SRG -> Era.MODERN;
            case SEARGE -> Era.FLATTENING;
            default -> Era.UNKNOWN;
        };
    }

    /**
     * The naming scheme in the bytecode is the hardest evidence available: a mod
     * calling {@code func_71410_x} was compiled against MCP for 1.7–1.12 and
     * cannot have been anything else.
     */
    private Era fromNamespace(ScanResult scan) {
        MappingNamespace namespace = scan.bytecodeNamespace();

        return switch (namespace) {
            case SEARGE -> Era.SEARGE;
            case SRG -> Era.MODERN;
            case INTERMEDIARY -> scan.javaVersion() >= 21 ? Era.CURRENT
                    : scan.javaVersion() >= 17 ? Era.MODERN : Era.FLATTENING;
            case OFFICIAL -> fromJavaVersion(scan.javaVersion());
            case MOJANG -> scan.javaVersion() >= 25 ? Era.UNOBFUSCATED
                    : scan.javaVersion() >= 21 ? Era.CURRENT
                    : scan.javaVersion() >= 16 ? Era.MODERN : Era.FLATTENING;
            case UNKNOWN -> Era.UNKNOWN;
        };
    }

    /** What the packaging format alone implies. */
    private Era fromFormat(ModFormat format) {
        return switch (format) {
            case NEOFORGE -> Era.CURRENT;
            case QUILT -> Era.MODERN;
            case LITELOADER, LEGACY_FORGE -> Era.SEARGE;
            case RIFT -> Era.FLATTENING;
            default -> Era.UNKNOWN;
        };
    }

    /**
     * Minecraft's own Java requirement moved in known steps, so the class file
     * version puts a floor under how new a mod can be.
     */
    private Era fromJavaVersion(int javaVersion) {
        if (javaVersion <= 0) {
            return Era.UNKNOWN;
        }

        if (javaVersion >= 25) {
            return Era.UNOBFUSCATED;
        }

        if (javaVersion >= 21) {
            return Era.CURRENT;
        }

        if (javaVersion >= 16) {
            return Era.MODERN;
        }

        if (javaVersion >= 9) {
            return Era.FLATTENING;
        }

        if (javaVersion == 8) {
            // 1.7 through 1.16 all shipped on Java 8; the Searge years dominate.
            return Era.SEARGE;
        }

        if (javaVersion >= 6) {
            return Era.FML;
        }

        return Era.MODLOADER;
    }

    /** Turns {@code [1.12,1.13)} or {@code >=1.16.5} into something Era can read. */
    private static String stripRange(String raw) {
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);

            if (Character.isLetterOrDigit(c) || c == '.') {
                out.append(c);
            } else if (out.length() > 0) {
                break;
            }
        }

        return out.toString();
    }
}
