package net.minecraftforge.fml;

/** Which half of the game some code is running for: the one that draws, or the one that decides. */
public enum LogicalSide {
    CLIENT,
    SERVER;

    public boolean isServer() {
        return this == SERVER;
    }

    public boolean isClient() {
        return this == CLIENT;
    }
}
