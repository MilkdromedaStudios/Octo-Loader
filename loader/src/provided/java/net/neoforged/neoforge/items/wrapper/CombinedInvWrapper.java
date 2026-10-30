package net.neoforged.neoforge.items.wrapper;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** Several inventories seen as one, their slots end to end. */
public class CombinedInvWrapper implements IItemHandlerModifiable {
    protected final IItemHandlerModifiable[] itemHandler;
    protected final int[] baseIndex;
    protected final int slotCount;

    public CombinedInvWrapper(IItemHandlerModifiable... itemHandler) {
        this.itemHandler = itemHandler;
        this.baseIndex = new int[itemHandler.length];
        int index = 0;

        for (int i = 0; i < itemHandler.length; i++) {
            index += itemHandler[i].getSlots();
            baseIndex[i] = index;
        }

        this.slotCount = index;
    }

    protected int getIndexForSlot(int slot) {
        if (slot < 0) {
            return -1;
        }

        for (int i = 0; i < baseIndex.length; i++) {
            if (slot - baseIndex[i] < 0) {
                return i;
            }
        }

        return -1;
    }

    protected IItemHandlerModifiable getHandlerFromIndex(int index) {
        return index < 0 || index >= itemHandler.length ? (IItemHandlerModifiable) EmptyItemHandler.INSTANCE
                : itemHandler[index];
    }

    protected int getSlotFromIndex(int slot, int index) {
        return index <= 0 || index >= baseIndex.length ? slot : slot - baseIndex[index - 1];
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        int index = getIndexForSlot(slot);
        getHandlerFromIndex(index).setStackInSlot(getSlotFromIndex(slot, index), stack);
    }

    @Override
    public int getSlots() {
        return slotCount;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        int index = getIndexForSlot(slot);
        return getHandlerFromIndex(index).getStackInSlot(getSlotFromIndex(slot, index));
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        int index = getIndexForSlot(slot);
        return getHandlerFromIndex(index).insertItem(getSlotFromIndex(slot, index), stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        int index = getIndexForSlot(slot);
        return getHandlerFromIndex(index).extractItem(getSlotFromIndex(slot, index), amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        int index = getIndexForSlot(slot);
        return getHandlerFromIndex(index).getSlotLimit(getSlotFromIndex(slot, index));
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        int index = getIndexForSlot(slot);
        return getHandlerFromIndex(index).isItemValid(getSlotFromIndex(slot, index), stack);
    }
}
