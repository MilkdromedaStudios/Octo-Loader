package net.minecraft.world.entity.player;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

/** Compile-time shape of the player's inventory. */
public abstract class Inventory implements net.minecraft.world.Container {
    public native boolean add(ItemStack stack);

    public native NonNullList<ItemStack> getNonEquipmentItems();
}
