package net.minecraft.world.item.crafting;

import net.minecraft.world.item.ItemStack;

/** Compile-time shape of the game's recipe input. */
public interface RecipeInput {
    ItemStack getItem(int slot);

    int size();
}
