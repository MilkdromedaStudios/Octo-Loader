package net.neoforged.neoforge.items;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** A menu slot backed by an item inventory, as NeoForge 21.1 had it. */
public class SlotItemHandler extends net.minecraft.world.inventory.Slot {
    private final IItemHandler itemHandler;
    // Not "index": a subclass reading this.index means the menu slot number Slot has, and a
    // private field of that name here would stand in its way (Create's toolbox slots).
    private final int handlerSlot;

    public SlotItemHandler(IItemHandler itemHandler, int index, int xPosition, int yPosition) {
        super(emptyInventory(), index, xPosition, yPosition);
        this.itemHandler = itemHandler;
        this.handlerSlot = index;
    }

    /** The slot's own container is never read: every member below answers from the handler. */
    private static net.minecraft.world.Container emptyInventory() {
        try {
            return (net.minecraft.world.Container) Class.forName("net.minecraft.world.SimpleContainer", true,
                    SlotItemHandler.class.getClassLoader()).getConstructor(int.class).newInstance(0);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not make an empty container", e);
        }
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return !stack.isEmpty() && itemHandler.isItemValid(handlerSlot, stack);
    }

    @Override
    public ItemStack getItem() {
        return getItemHandler().getStackInSlot(handlerSlot);
    }

    @Override
    public void set(ItemStack stack) {
        ((IItemHandlerModifiable) getItemHandler()).setStackInSlot(handlerSlot, stack);
        setChanged();
    }

    public void initialize(ItemStack stack) {
        ((IItemHandlerModifiable) getItemHandler()).setStackInSlot(handlerSlot, stack);
        setChanged();
    }

    @Override
    public int getMaxStackSize() {
        return itemHandler.getSlotLimit(handlerSlot);
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        ItemStack maxAdd = stack.copy();
        int maxInput = stack.getMaxStackSize();
        maxAdd.setCount(maxInput);

        IItemHandler handler = getItemHandler();
        ItemStack current = handler.getStackInSlot(handlerSlot);

        if (handler instanceof IItemHandlerModifiable modifiable) {
            modifiable.setStackInSlot(handlerSlot, ItemStack.EMPTY);
            ItemStack remainder = modifiable.insertItem(handlerSlot, maxAdd, true);
            modifiable.setStackInSlot(handlerSlot, current);
            return maxInput - remainder.getCount();
        }

        ItemStack remainder = handler.insertItem(handlerSlot, maxAdd, true);
        return current.getCount() + maxInput - remainder.getCount();
    }

    @Override
    public boolean mayPickup(Player player) {
        return !getItemHandler().extractItem(handlerSlot, 1, true).isEmpty();
    }

    @Override
    public ItemStack remove(int amount) {
        return getItemHandler().extractItem(handlerSlot, amount, false);
    }

    public IItemHandler getItemHandler() {
        return itemHandler;
    }
}
