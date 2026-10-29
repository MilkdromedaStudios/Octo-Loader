package net.minecraft.octotest;

/** Where {@link Paint3}'s factories and constants live now. */
public final class Paint3s {
    public static final Paint3 RED = new Paint3("red");

    private Paint3s() {
    }

    public static Paint3 of(String colour) {
        return new Paint3(colour);
    }
}
