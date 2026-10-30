package net.neoforged.neoforge.items;

import net.minecraft.world.item.ItemStack;

/** An item inventory whose slots can be set outright. */
public interface IItemHandlerModifiable extends IItemHandler {
    void setStackInSlot(int slot, ItemStack stack);
}
