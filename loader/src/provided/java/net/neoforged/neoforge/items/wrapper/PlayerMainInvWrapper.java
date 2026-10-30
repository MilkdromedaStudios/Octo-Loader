package net.neoforged.neoforge.items.wrapper;

import net.minecraft.world.entity.player.Inventory;

/** The player's main inventory — the 36 slots that are not equipment — as an item inventory. */
public class PlayerMainInvWrapper extends RangedWrapper {
    private final Inventory inventoryPlayer;

    public PlayerMainInvWrapper(Inventory inv) {
        super(new InvWrapper(inv), 0, inv.getNonEquipmentItems().size());
        this.inventoryPlayer = inv;
    }

    public Inventory getInventoryPlayer() {
        return inventoryPlayer;
    }
}
