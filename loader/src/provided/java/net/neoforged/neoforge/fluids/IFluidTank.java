package net.neoforged.neoforge.fluids;

import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** A single tank of fluid, as NeoForge 21.1 had it. */
public interface IFluidTank {
    FluidStack getFluid();

    int getFluidAmount();

    int getCapacity();

    boolean isFluidValid(FluidStack stack);

    int fill(FluidStack resource, IFluidHandler.FluidAction action);

    FluidStack drain(int maxDrain, IFluidHandler.FluidAction action);

    FluidStack drain(FluidStack resource, IFluidHandler.FluidAction action);
}
