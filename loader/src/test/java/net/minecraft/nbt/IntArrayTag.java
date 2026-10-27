package net.minecraft.nbt;

/** The game's int array tag. */
public class IntArrayTag {
    private final int[] values;

    public IntArrayTag(int[] values) {
        this.values = values;
    }

    public int[] getAsIntArray() {
        return values;
    }
}
