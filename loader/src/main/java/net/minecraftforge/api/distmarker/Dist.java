package net.minecraftforge.api.distmarker;

/** Which side the game is running as. Provided by Octo for Forge mods. */
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
