package net.minecraft.octotest;

/** Fields that were public until a release closed them, some behind accessors. */
public class Level3 {
    private static String motd = "hello";

    private final boolean isClientSide;
    private int age = 7;
    protected String name = "level";

    public Level3(boolean isClientSide) {
        this.isClientSide = isClientSide;
    }

    public static String getMotd() {
        return motd;
    }

    public boolean isClientSide() {
        return isClientSide;
    }
}
