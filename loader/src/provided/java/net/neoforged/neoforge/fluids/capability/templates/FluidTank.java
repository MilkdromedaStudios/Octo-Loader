package net.neoforged.neoforge.fluids.capability.templates;

import java.util.function.Predicate;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.IFluidTank;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import studios.milkdromeda.octo.transform.Shims;

/**
 * NeoForge 21.1's single fluid tank, which Create's tanks, pipes and
 * basins extend and most mods with fluids build on. Saved as NeoForge saved
 * it, under {@code Fluid}.
 */
public class FluidTank implements IFluidHandler, IFluidTank {
    protected Predicate<FluidStack> validator;
    protected FluidStack fluid = FluidStack.EMPTY;
    protected int capacity;

    public FluidTank(int capacity) {
        this(capacity, stack -> true);
    }

    public FluidTank(int capacity, Predicate<FluidStack> validator) {
        this.capacity = capacity;
        this.validator = validator;
    }

    public FluidTank setCapacity(int capacity) {
        this.capacity = capacity;
        return this;
    }

    public FluidTank setValidator(Predicate<FluidStack> validator) {
        if (validator != null) {
            this.validator = validator;
        }

        return this;
    }

    @Override
    public boolean isFluidValid(FluidStack stack) {
        return validator.test(stack);
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    @Override
    public FluidStack getFluid() {
        return fluid;
    }

    @Override
    public int getFluidAmount() {
        return fluid.getAmount();
    }

    public FluidTank readFromNBT(HolderLookup.Provider provider, CompoundTag nbt) {
        fluid = (FluidStack) Shims.stackParseOptional(provider, nbt.getCompoundOrEmpty("Fluid"), FluidStack.class);
        return this;
    }

    public CompoundTag writeToNBT(HolderLookup.Provider provider, CompoundTag nbt) {
        if (!fluid.isEmpty()) {
            nbt.put("Fluid", (Tag) Shims.stackSave(fluid, provider));
        }

        return nbt;
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return getFluid();
    }

    @Override
    public int getTankCapacity(int tank) {
        return getCapacity();
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return isFluidValid(stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty() || !isFluidValid(resource)) {
            return 0;
        }

        if (action.simulate()) {
            if (fluid.isEmpty()) {
                return Math.min(capacity, resource.getAmount());
            }

            if (!FluidStack.isSameFluidSameComponents(fluid, resource)) {
                return 0;
            }

            return Math.min(capacity - fluid.getAmount(), resource.getAmount());
        }

        if (fluid.isEmpty()) {
            fluid = resource.copyWithAmount(Math.min(capacity, resource.getAmount()));
            onContentsChanged();
            return fluid.getAmount();
        }

        if (!FluidStack.isSameFluidSameComponents(fluid, resource)) {
            return 0;
        }

        int filled = capacity - fluid.getAmount();

        if (resource.getAmount() < filled) {
            fluid.grow(resource.getAmount());
            filled = resource.getAmount();
        } else {
            fluid.setAmount(capacity);
        }

        if (filled > 0) {
            onContentsChanged();
        }

        return filled;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource.isEmpty() || !FluidStack.isSameFluidSameComponents(resource, fluid)) {
            return FluidStack.EMPTY;
        }

        return drain(resource.getAmount(), action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        int drained = Math.min(maxDrain, fluid.getAmount());
        FluidStack stack = fluid.copyWithAmount(drained);

        if (action.execute() && drained > 0) {
            fluid.shrink(drained);

            if (fluid.getAmount() <= 0) {
                fluid = FluidStack.EMPTY;
            }

            onContentsChanged();
        }

        return stack;
    }

    protected void onContentsChanged() {
    }

    public void setFluid(FluidStack stack) {
        this.fluid = stack;
    }

    public boolean isEmpty() {
        return fluid.isEmpty();
    }

    public int getSpace() {
        return Math.max(0, capacity - fluid.getAmount());
    }
}
