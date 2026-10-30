package net.neoforged.neoforge.fluids.capability;

import net.minecraft.world.item.ItemStack;

/** A fluid container that is an item: a bucket, a tank item. */
public interface IFluidHandlerItem extends IFluidHandler {
    ItemStack getContainer();
}
