package net.neoforged.fml.loading;

/**
 * The versions NeoForge reports about itself and the game.
 *
 * <p>Mods read these to decide which code path to take, so the NeoForge
 * version is the one Octo stands in for — the same one the mod resolver
 * checked their dependency ranges against.
 */
public record VersionInfo(String neoForgeVersion, String fmlVersion, String mcVersion, String neoFormVersion) {
}
