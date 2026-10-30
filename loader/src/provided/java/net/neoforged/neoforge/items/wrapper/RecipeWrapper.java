package net.neoforged.neoforge.items.wrapper;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeInput;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** An inventory handed to a recipe as its input. */
public class RecipeWrapper implements RecipeInput {
    protected final IItemHandlerModifiable inv;

    public RecipeWrapper(IItemHandlerModifiable inv) {
        this.inv = inv;
    }

    @Override
    public int size() {
        return inv.getSlots();
    }

    @Override
    public ItemStack getItem(int slot) {
        return inv.getStackInSlot(slot);
    }

    public ItemStack removeItem(int slot, int count) {
        ItemStack stack = inv.getStackInSlot(slot);
        return stack.isEmpty() ? ItemStack.EMPTY : stack.split(count);
    }

    public void setItem(int slot, ItemStack stack) {
        inv.setStackInSlot(slot, stack);
    }

    public int getContainerSize() {
        return inv.getSlots();
    }

    public boolean isEmpty() {
        for (int i = 0; i < inv.getSlots(); i++) {
            if (!inv.getStackInSlot(i).isEmpty()) {
                return false;
            }
        }

        return true;
    }
}
