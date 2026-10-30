package net.minecraft.world;

import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/** Compile-time shape of the game's sided container. */
public interface WorldlyContainer extends Container {
    int[] getSlotsForFace(Direction side);

    boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side);

    boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side);
}
