package net.minecraft.octotest;

/** Stands in for 26.x's {@code WoodType}: a value with constants of its own type, and no default. */
public final class Wood3 {
    public static final Wood3 OAK = new Wood3("oak");
    public static final Wood3 BIRCH = new Wood3("birch");

    public final String name;

    private Wood3(String name) {
        this.name = name;
    }
}
