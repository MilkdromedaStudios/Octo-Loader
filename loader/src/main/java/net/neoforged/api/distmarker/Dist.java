package net.neoforged.api.distmarker;

/** Which side the game is running as. Provided by Octo for NeoForge mods. */
public enum Dist {
    CLIENT,
    DEDICATED_SERVER;

    public boolean isClient() {
        return this == CLIENT;
    }

    public boolean isDedicatedServer() {
        return this == DEDICATED_SERVER;
    }
}
