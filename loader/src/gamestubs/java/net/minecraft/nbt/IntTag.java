package net.minecraft.nbt;

/** Compile-time shape of the game's int tag. */
public final class IntTag implements NumericTag {
    public static native IntTag valueOf(int value);

    @Override
    public native int intValue();
}
