package net.neoforged.neoforge.items;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.INBTSerializable;

import studios.milkdromeda.octo.transform.Shims;

/**
 * NeoForge 21.1's inventory of item stacks, which nearly every mod with a
 * machine, a chest or a backpack builds on or extends. Saved as NeoForge
 * saved it — {@code Items} with a {@code Slot} each, and {@code Size} — so a
 * world's contents read back.
 */
public class ItemStackHandler implements IItemHandler, IItemHandlerModifiable, INBTSerializable<CompoundTag> {
    protected NonNullList<ItemStack> stacks;

    public ItemStackHandler() {
        this(1);
    }

    public ItemStackHandler(int size) {
        stacks = NonNullList.withSize(size, ItemStack.EMPTY);
    }

    public ItemStackHandler(NonNullList<ItemStack> stacks) {
        this.stacks = stacks;
    }

    public void setSize(int size) {
        stacks = NonNullList.withSize(size, ItemStack.EMPTY);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        validateSlotIndex(slot);
        stacks.set(slot, stack);
        onContentsChanged(slot);
    }

    @Override
    public int getSlots() {
        return stacks.size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        validateSlotIndex(slot);
        return stacks.get(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        if (!isItemValid(slot, stack)) {
            return stack;
        }

        validateSlotIndex(slot);
        ItemStack existing = stacks.get(slot);
        int limit = getStackLimit(slot, stack);

        if (!existing.isEmpty()) {
            if (!ItemStack.isSameItemSameComponents(stack, existing)) {
                return stack;
            }

            limit -= existing.getCount();
        }

        if (limit <= 0) {
            return stack;
        }

        boolean reachedLimit = stack.getCount() > limit;

        if (!simulate) {
            if (existing.isEmpty()) {
                stacks.set(slot, reachedLimit ? stack.copyWithCount(limit) : stack);
            } else {
                existing.grow(reachedLimit ? limit : stack.getCount());
            }

            onContentsChanged(slot);
        }

        return reachedLimit ? stack.copyWithCount(stack.getCount() - limit) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount == 0) {
            return ItemStack.EMPTY;
        }

        validateSlotIndex(slot);
        ItemStack existing = stacks.get(slot);

        if (existing.isEmpty()) {
            return ItemStack.EMPTY;
        }

        int toExtract = Math.min(amount, existing.getMaxStackSize());

        if (existing.getCount() <= toExtract) {
            if (!simulate) {
                stacks.set(slot, ItemStack.EMPTY);
                onContentsChanged(slot);
                return existing;
            }

            return existing.copy();
        }

        if (!simulate) {
            stacks.set(slot, existing.copyWithCount(existing.getCount() - toExtract));
            onContentsChanged(slot);
        }

        return existing.copyWithCount(toExtract);
    }

    @Override
    public int getSlotLimit(int slot) {
        return 99;
    }

    protected int getStackLimit(int slot, ItemStack stack) {
        return Math.min(getSlotLimit(slot), stack.getMaxStackSize());
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return true;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        ListTag items = new ListTag();

        for (int i = 0; i < stacks.size(); i++) {
            if (!stacks.get(i).isEmpty()) {
                CompoundTag item = new CompoundTag();
                item.putInt("Slot", i);
                items.add((Tag) Shims.stackSaveInto(stacks.get(i), provider, item));
            }
        }

        CompoundTag nbt = new CompoundTag();
        nbt.put("Items", items);
        nbt.putInt("Size", stacks.size());
        return nbt;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {
        setSize(nbt.contains("Size") ? nbt.getIntOr("Size", stacks.size()) : stacks.size());
        ListTag items = nbt.getListOrEmpty("Items");

        for (int i = 0; i < items.size(); i++) {
            CompoundTag item = items.getCompoundOrEmpty(i);
            int slot = item.getIntOr("Slot", -1);

            if (slot >= 0 && slot < stacks.size()) {
                stacks.set(slot, (ItemStack) Shims.stackParseOptional(provider, item, ItemStack.class));
            }
        }

        onLoad();
    }

    protected void validateSlotIndex(int slot) {
        if (slot < 0 || slot >= stacks.size()) {
            throw new RuntimeException("Slot " + slot + " not in valid range - [0," + stacks.size() + ")");
        }
    }

    protected void onLoad() {
    }

    protected void onContentsChanged(int slot) {
    }
}
