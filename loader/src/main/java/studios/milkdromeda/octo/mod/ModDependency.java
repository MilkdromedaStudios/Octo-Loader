package studios.milkdromeda.octo.mod;

import studios.milkdromeda.octo.mod.version.VersionPredicate;

/**
 * A relationship between two mods.
 *
 * @param modId     the other mod
 * @param versions  the accepted version range
 * @param kind      how strongly it is required, or that it must be absent
 * @param ordering  load-order hint, from Forge's {@code ordering} field
 */
public record ModDependency(String modId, VersionPredicate versions, Kind kind, Ordering ordering) {
    public enum Kind {
        /** Hard requirement; the mod does not load without it. */
        REQUIRED,
        /** Soft requirement; a warning if absent. */
        OPTIONAL,
        /** Must not be present at this version. */
        CONFLICTS,
        /** Must not be present at all. */
        BREAKS;

        public boolean isNegative() {
            return this == CONFLICTS || this == BREAKS;
        }
    }

    public enum Ordering { BEFORE, AFTER, NONE }

    public static ModDependency required(String modId, String range) {
        return new ModDependency(modId, VersionPredicate.parse(range), Kind.REQUIRED, Ordering.NONE);
    }

    public static ModDependency optional(String modId, String range) {
        return new ModDependency(modId, VersionPredicate.parse(range), Kind.OPTIONAL, Ordering.NONE);
    }

    public ModDependency withKind(Kind newKind) {
        return new ModDependency(modId, versions, newKind, ordering);
    }

    public ModDependency withVersions(VersionPredicate newVersions) {
        return new ModDependency(modId, newVersions, kind, ordering);
    }

    /**
     * {@code true} for the pseudo-mods every ecosystem publishes for the game and
     * the loader itself. Matched without regard to case, because mod ids were not
     * consistently lowercase until around 1.13 and {@code Forge} is common.
     */
    public boolean isPlatformDependency() {
        return switch (modId.toLowerCase(java.util.Locale.ROOT)) {
            case "minecraft", "java", "forge", "neoforge", "fml", "fabricloader", "fabric",
                 "fabric-api", "fabric-api-base", "quilt_loader", "quilt_base", "qsl",
                 "liteloader", "octoloader" -> true;
            default -> false;
        };
    }

    @Override
    public String toString() {
        return kind + " " + modId + " " + versions;
    }
}
