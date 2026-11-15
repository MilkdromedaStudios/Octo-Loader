package net.minecraft.world.item;

/** The sword class mods extended until 1.21.5. */
public class SwordItem extends TieredItem {
    public SwordItem(Tier tier, int attackDamage, float attackSpeed, Item.Properties properties) {
        super(tier, material(tier).applySwordProperties(properties, (float) attackDamage, attackSpeed));
    }

    /** As 1.21 and 1.21.1 had it, its attributes already in the properties. */
    public SwordItem(Tier tier, Item.Properties properties) {
        super(tier, properties);
    }

    public SwordItem(ToolMaterial material, float attackDamage, float attackSpeed, Item.Properties properties) {
        super(null, material.applySwordProperties(properties, attackDamage, attackSpeed));
    }
}
