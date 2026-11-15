package net.minecraft.world.item;

/** The shape of the tool tier interface the game had until 1.21.2, as far as Octo's own tool classes read it. */
public interface Tier {
    int getUses();

    float getSpeed();

    float getAttackDamageBonus();

    int getEnchantmentValue();
}
