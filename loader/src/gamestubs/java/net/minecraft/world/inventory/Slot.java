package net.minecraft.world.inventory;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Compile-time shape of the game's inventory slot; members as 26.x has them. */
public class Slot {
    public final Container container = null;
    public int index;

    public Slot(Container container, int index, int x, int y) {
    }

    public native boolean mayPlace(ItemStack stack);

    public native ItemStack getItem();

    public native void set(ItemStack stack);

    public native void setChanged();

    public native int getMaxStackSize();

    public native int getMaxStackSize(ItemStack stack);

    public native boolean mayPickup(Player player);

    public native ItemStack remove(int amount);
}
