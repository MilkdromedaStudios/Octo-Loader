package net.neoforged.neoforge.items;

import net.minecraft.world.item.ItemStack;

/**
 * NeoForge's item inventory, as mods used it until NeoForge 26 replaced it
 * with the transfer API's resource handlers. Provided by Octo for the mods
 * built against it; the contract is NeoForge 21.1's.
 */
public interface IItemHandler {
    int getSlots();

    ItemStack getStackInSlot(int slot);

    ItemStack insertItem(int slot, ItemStack stack, boolean simulate);

    ItemStack extractItem(int slot, int amount, boolean simulate);

    int getSlotLimit(int slot);

    boolean isItemValid(int slot, ItemStack stack);
}
