package net.minecraft.world;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Compile-time shape of the game's container. */
public interface Container {
    int getContainerSize();

    ItemStack getItem(int slot);

    ItemStack removeItem(int slot, int amount);

    void setItem(int slot, ItemStack stack);

    int getMaxStackSize();

    void setChanged();

    boolean stillValid(Player player);

    boolean canPlaceItem(int slot, ItemStack stack);
}
