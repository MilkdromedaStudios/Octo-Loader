package net.neoforged.neoforge.items.wrapper;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** A game container seen as an item inventory, as NeoForge 21.1 had it. */
public class InvWrapper implements IItemHandlerModifiable {
    private final Container inv;

    public InvWrapper(Container inv) {
        this.inv = inv;
    }

    public Container getInv() {
        return inv;
    }

    @Override
    public int getSlots() {
        return inv.getContainerSize();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return inv.getItem(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack inSlot = inv.getItem(slot);

        if (!inSlot.isEmpty()) {
            if (inSlot.getCount() >= Math.min(inSlot.getMaxStackSize(), getSlotLimit(slot))
                    || !ItemStack.isSameItemSameComponents(stack, inSlot) || !inv.canPlaceItem(slot, stack)) {
                return stack;
            }

            int room = Math.min(stack.getMaxStackSize(), getSlotLimit(slot)) - inSlot.getCount();

            if (stack.getCount() <= room) {
                if (!simulate) {
                    ItemStack copy = stack.copy();
                    copy.grow(inSlot.getCount());
                    inv.setItem(slot, copy);
                    inv.setChanged();
                }

                return ItemStack.EMPTY;
            }

            ItemStack rest = stack.copy();

            if (!simulate) {
                ItemStack copy = rest.split(room);
                copy.grow(inSlot.getCount());
                inv.setItem(slot, copy);
                inv.setChanged();
            } else {
                rest.shrink(room);
            }

            return rest;
        }

        if (!inv.canPlaceItem(slot, stack)) {
            return stack;
        }

        int room = Math.min(stack.getMaxStackSize(), getSlotLimit(slot));

        if (room < stack.getCount()) {
            ItemStack rest = stack.copy();

            if (!simulate) {
                inv.setItem(slot, rest.split(room));
                inv.setChanged();
            } else {
                rest.shrink(room);
            }

            return rest;
        }

        if (!simulate) {
            inv.setItem(slot, stack);
            inv.setChanged();
        }

        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount == 0) {
            return ItemStack.EMPTY;
        }

        ItemStack inSlot = inv.getItem(slot);

        if (inSlot.isEmpty()) {
            return ItemStack.EMPTY;
        }

        if (simulate) {
            return inSlot.copyWithCount(Math.min(inSlot.getCount(), amount));
        }

        int taken = Math.min(inSlot.getCount(), amount);
        ItemStack removed = inv.removeItem(slot, taken);
        inv.setChanged();
        return removed;
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        inv.setItem(slot, stack);
    }

    @Override
    public int getSlotLimit(int slot) {
        return inv.getMaxStackSize();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return inv.canPlaceItem(slot, stack);
    }
}
