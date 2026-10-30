package net.neoforged.neoforge.fluids;

import net.minecraft.world.level.material.Fluid;

/** Compile-time shape of NeoForge's fluid stack; members as NeoForge 26 has them. */
public final class FluidStack {
    public static final FluidStack EMPTY = null;

    public native boolean isEmpty();

    public native int getAmount();

    public native void setAmount(int amount);

    public native FluidStack copy();

    public native FluidStack copyWithAmount(int amount);

    public native void grow(int amount);

    public native void shrink(int amount);

    public native Fluid getFluid();

    public static native boolean isSameFluid(FluidStack first, FluidStack second);

    public static native boolean isSameFluidSameComponents(FluidStack first, FluidStack second);
}
