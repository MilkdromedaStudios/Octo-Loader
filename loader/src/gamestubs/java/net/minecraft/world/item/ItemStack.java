package net.minecraft.world.item;

/** Compile-time shape of the game's item stack; members as 26.x has them. */
public final class ItemStack {
    public static final ItemStack EMPTY = null;

    public native boolean isEmpty();

    public native int getCount();

    public native void setCount(int count);

    public native int getMaxStackSize();

    public native ItemStack copy();

    public native ItemStack copyWithCount(int count);

    public native void grow(int amount);

    public native void shrink(int amount);

    public native ItemStack split(int amount);

    public native Item getItem();

    public static native boolean isSameItemSameComponents(ItemStack first, ItemStack second);
}
