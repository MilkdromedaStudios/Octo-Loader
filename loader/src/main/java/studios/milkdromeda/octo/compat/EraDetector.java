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
        Era declared = fromDeclaration(metadata);

        if (declared.isKnown()) {
            return declared;
        }

        Era fromBytecode = scan == null ? Era.UNKNOWN : fromNamespace(scan);

        if (fromBytecode.isKnown()) {
            return fromBytecode;
        }

        Era fromFormat = fromFormat(metadata.format());

        if (fromFormat.isKnown()) {
            return fromFormat;
        }

        return scan == null ? Era.UNKNOWN : fromJavaVersion(scan.javaVersion());
    }

    /** The author's own statement, when there is one. */
    private Era fromDeclaration(ModMetadata metadata) {
        String declared = metadata.declaredMinecraftVersion().orElse(null);

        if (declared != null) {
            Era era = Era.forMinecraftVersion(stripRange(declared));

            if (era.isKnown()) {
                return era;
            }
        }

        ModDependency minecraft = metadata.minecraftDependency().orElse(null);

        if (minecraft != null && !minecraft.versions().isAny()) {
            Era era = Era.forMinecraftVersion(minecraft.versions().lowerBound().raw());

            if (era.isKnown()) {
                return era;
            }
        }

        return Era.UNKNOWN;
    }

    /**
     * The naming scheme in the bytecode is the hardest evidence available: a mod
     * calling {@code func_71410_x} was compiled against MCP for 1.7–1.12 and
     * cannot have been anything else.
     */
    private Era fromNamespace(ScanResult scan) {
        MappingNamespace namespace = scan.dominantNamespace();

        return switch (namespace) {
            case SEARGE -> Era.SEARGE;
            case INTERMEDIARY -> scan.javaVersion() >= 17 ? Era.MODERN : Era.FLATTENING;
            case OFFICIAL -> fromJavaVersion(scan.javaVersion());
            case MOJANG -> scan.javaVersion() >= 21 ? Era.CURRENT : scan.javaVersion() >= 16 ? Era.MODERN : Era.FLATTENING;
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
