package net.minecraftforge.fml.relauncher;

import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * The side, as Forge said it from 1.7 to 1.12. Mods of that era branch on it
 * before anything else — AppleSkin decides with it whether to register its
 * client handlers — so a null here sent every one of them down the wrong path.
 */
public enum Side {
    CLIENT,
    SERVER;

    public boolean isClient() {
        return this == CLIENT;
    }

    public boolean isServer() {
        return this == SERVER;
    }

    /** The side this game is running as. */
    public static Side current() {
        return OctoRuntime.isRunning() && OctoRuntime.get().side() == studios.milkdromeda.octo.mod.Side.CLIENT
                ? CLIENT : SERVER;
    }
}
