package studios.milkdromeda.octo.mod;

import java.util.Locale;

/** Physical side the game is running on, and the side a mod declares it needs. */
public enum Side {
    CLIENT,
    SERVER,
    BOTH;

    public boolean runsOn(Side physical) {
        return this == BOTH || this == physical;
    }

    /**
     * Parses the side out of any of the spellings the four ecosystems use:
     * Fabric {@code client/server/*}, Forge {@code CLIENT/SERVER/BOTH},
     * Quilt {@code client/dedicated_server/*}.
     */
    public static Side parse(String raw, Side fallback) {
        if (raw == null) {
            return fallback;
        }

        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "client", "clientonly" -> CLIENT;
            case "server", "dedicated_server", "serveronly" -> SERVER;
            case "both", "*", "universal", "common" -> BOTH;
            default -> fallback;
        };
    }
}
