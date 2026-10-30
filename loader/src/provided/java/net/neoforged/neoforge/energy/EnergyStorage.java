package net.neoforged.neoforge.energy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/** NeoForge 21.1's energy buffer, saved as a bare int as NeoForge saved it. */
public class EnergyStorage implements IEnergyStorage, INBTSerializable<Tag> {
    protected int energy;
    protected int capacity;
    protected int maxReceive;
    protected int maxExtract;

    public EnergyStorage(int capacity) {
        this(capacity, capacity, capacity, 0);
    }

    public EnergyStorage(int capacity, int maxTransfer) {
        this(capacity, maxTransfer, maxTransfer, 0);
    }

    public EnergyStorage(int capacity, int maxReceive, int maxExtract) {
        this(capacity, maxReceive, maxExtract, 0);
    }

    public EnergyStorage(int capacity, int maxReceive, int maxExtract, int energy) {
        this.capacity = capacity;
        this.maxReceive = maxReceive;
        this.maxExtract = maxExtract;
        this.energy = Math.max(0, Math.min(capacity, energy));
    }

    @Override
    public int receiveEnergy(int toReceive, boolean simulate) {
        if (!canReceive() || toReceive <= 0) {
            return 0;
        }

        int received = Math.max(0, Math.min(capacity - energy, Math.min(maxReceive, toReceive)));

        if (!simulate) {
            energy += received;
        }

        return received;
    }

    @Override
    public int extractEnergy(int toExtract, boolean simulate) {
        if (!canExtract() || toExtract <= 0) {
            return 0;
        }

        int extracted = Math.min(energy, Math.min(maxExtract, toExtract));

        if (!simulate) {
            energy -= extracted;
        }

        return extracted;
    }

    @Override
    public int getEnergyStored() {
        return energy;
    }

    @Override
    public int getMaxEnergyStored() {
        return capacity;
    }

    @Override
    public boolean canExtract() {
        return maxExtract > 0;
    }

    @Override
    public boolean canReceive() {
        return maxReceive > 0;
    }

    @Override
    public Tag serializeNBT(HolderLookup.Provider provider) {
        return IntTag.valueOf(getEnergyStored());
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, Tag nbt) {
        if (nbt instanceof NumericTag number) {
            energy = number.intValue();
        }
    }
}
