package net.neoforged.neoforge.items;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** NeoForge 21.1's helpers for moving stacks in and out of item inventories. */
public class ItemHandlerHelper {
    public static ItemStack insertItem(IItemHandler dest, ItemStack stack, boolean simulate) {
        if (dest == null || stack.isEmpty()) {
            return stack;
        }

        for (int i = 0; i < dest.getSlots(); i++) {
            stack = dest.insertItem(i, stack, simulate);

            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }

        return stack;
    }

    public static boolean canItemStacksStack(ItemStack a, ItemStack b) {
        return !a.isEmpty() && ItemStack.isSameItemSameComponents(a, b);
    }

    public static boolean canItemStacksStackRelaxed(ItemStack a, ItemStack b) {
        return canItemStacksStack(a, b);
    }

    public static ItemStack copyStackWithSize(ItemStack stack, int size) {
        return size == 0 ? ItemStack.EMPTY : stack.copyWithCount(size);
    }

    public static ItemStack insertItemStacked(IItemHandler inventory, ItemStack stack, boolean simulate) {
        if (inventory == null || stack.isEmpty()) {
            return stack;
        }

        // Into stacks of the same item first, then into empty slots.
        for (int i = 0; i < inventory.getSlots() && !stack.isEmpty(); i++) {
            if (canItemStacksStack(inventory.getStackInSlot(i), stack)) {
                stack = inventory.insertItem(i, stack, simulate);
            }
        }

        for (int i = 0; i < inventory.getSlots() && !stack.isEmpty(); i++) {
            if (inventory.getStackInSlot(i).isEmpty()) {
                stack = inventory.insertItem(i, stack, simulate);
            }
        }

        return stack;
    }

    public static void giveItemToPlayer(Player player, ItemStack stack) {
        giveItemToPlayer(player, stack, -1);
    }

    /** Into the player's inventory; what does not fit is dropped at their feet. */
    public static void giveItemToPlayer(Player player, ItemStack stack, int preferredSlot) {
        if (stack.isEmpty()) {
            return;
        }

        if (!player.getInventory().add(stack) && !stack.isEmpty()) {
            try {
                player.getClass().getMethod("drop", ItemStack.class, boolean.class).invoke(player, stack, false);
            } catch (ReflectiveOperationException e) {
                // Nothing to drop it with: the stack is lost, as NeoForge's would be with no level.
            }
        }
    }

    public static int calcRedstoneFromInventory(IItemHandler inventory) {
        if (inventory == null) {
            return 0;
        }

        int filled = 0;
        float proportion = 0f;

        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);

            if (!stack.isEmpty()) {
                proportion += (float) stack.getCount()
                        / (float) Math.min(inventory.getSlotLimit(i), stack.getMaxStackSize());
                filled++;
            }
        }

        proportion /= (float) inventory.getSlots();
        return (int) Math.floor(proportion * 14f) + (filled > 0 ? 1 : 0);
    }
}
