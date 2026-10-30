package net.neoforged.neoforge.energy;

/**
 * NeoForge's energy buffer, as mods used it until NeoForge 26 replaced it
 * with the transfer API's energy handler. Provided by Octo for the mods built
 * against it; the contract is NeoForge 21.1's.
 */
public interface IEnergyStorage {
    int receiveEnergy(int toReceive, boolean simulate);

    int extractEnergy(int toExtract, boolean simulate);

    int getEnergyStored();

    int getMaxEnergyStored();

    boolean canExtract();

    boolean canReceive();
}
