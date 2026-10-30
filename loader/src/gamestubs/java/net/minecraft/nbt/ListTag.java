package net.minecraft.nbt;

/** Compile-time shape of the game's list tag. */
public final class ListTag extends java.util.AbstractList<Tag> implements Tag {
    public ListTag() {
    }

    @Override
    public native Tag get(int index);

    @Override
    public native int size();

    public native CompoundTag getCompoundOrEmpty(int index);
}
