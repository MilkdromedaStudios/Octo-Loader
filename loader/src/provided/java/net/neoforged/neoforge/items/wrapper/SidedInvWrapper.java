package net.neoforged.neoforge.items.wrapper;

import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** The slots a sided container offers one face, seen as an item inventory. */
public class SidedInvWrapper implements IItemHandlerModifiable {
    protected final WorldlyContainer inv;
    protected final Direction side;

    public SidedInvWrapper(WorldlyContainer inv, Direction side) {
        this.inv = inv;
        this.side = side;
    }

    private int[] slots() {
        return inv.getSlotsForFace(side);
    }

    private int slot(int slot) {
        int[] slots = slots();
        return slot < 0 || slot >= slots.length ? -1 : slots[slot];
    }

    @Override
    public int getSlots() {
        return slots().length;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        int real = slot(slot);
        return real == -1 ? ItemStack.EMPTY : inv.getItem(real);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        int real = slot(slot);

        if (real == -1 || !inv.canPlaceItemThroughFace(real, stack, side)) {
            return stack;
        }

        return new InvWrapper(inv).insertItem(real, stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        int real = slot(slot);

        if (real == -1 || amount == 0) {
            return ItemStack.EMPTY;
        }

        ItemStack inSlot = inv.getItem(real);

        if (inSlot.isEmpty() || !inv.canTakeItemThroughFace(real, inSlot, side)) {
            return ItemStack.EMPTY;
        }

        return new InvWrapper(inv).extractItem(real, amount, simulate);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        int real = slot(slot);

        if (real != -1) {
            inv.setItem(real, stack);
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        return inv.getMaxStackSize();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        int real = slot(slot);
        return real != -1 && inv.canPlaceItem(real, stack);
    }
}
