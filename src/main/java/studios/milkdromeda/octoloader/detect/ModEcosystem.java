package studios.milkdromeda.octoloader.detect;

/**
 * The mod ecosystems Octo Loader can identify, ordered by detection priority:
 * when a jar carries several marker files (multi-loader jars are common), the
 * highest-priority marker wins.
 */
public enum ModEcosystem {
    /** Native Fabric mod ({@code fabric.mod.json}) — loaded by Fabric Loader itself. */
    FABRIC("Fabric", "fabric.mod.json"),
    /** Quilt mod ({@code quilt.mod.json}). */
    QUILT("Quilt", "quilt.mod.json"),
    /** NeoForge mod ({@code META-INF/neoforge.mods.toml}). */
    NEOFORGE("NeoForge", "META-INF/neoforge.mods.toml"),
    /** Forge mod, 1.13+ toml era ({@code META-INF/mods.toml}). */
    FORGE("Forge", "META-INF/mods.toml"),
    /** Legacy Forge mod, &le;1.12.2 ({@code mcmod.info}). */
    FORGE_LEGACY("Legacy Forge", "mcmod.info"),
    /** Paper plugin ({@code paper-plugin.yml}). */
    PAPER_PLUGIN("Paper plugin", "paper-plugin.yml"),
    /** Bukkit/Spigot plugin ({@code plugin.yml}). */
    BUKKIT_PLUGIN("Bukkit plugin", "plugin.yml"),
    /** No recognizable mod metadata. */
    UNKNOWN("Unknown", null);

    private final String displayName;
    private final String markerFile;

    ModEcosystem(String displayName, String markerFile) {
        this.displayName = displayName;
        this.markerFile = markerFile;
    }

    public String displayName() {
        return displayName;
    }

    public String markerFile() {
        return markerFile;
    }
}
