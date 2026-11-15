package net.minecraft.world.item;

import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/** The digging tool mods extended until 1.21.5, built the way 26.x builds one: from its properties. */
public class DiggerItem extends TieredItem {
    /** As 1.20 had it. */
    public DiggerItem(float attackDamage, float attackSpeed, Tier tier, TagKey<Block> mineable,
            Item.Properties properties) {
        super(tier, material(tier).applyToolProperties(properties, mineable, attackDamage, attackSpeed, 0.0F));
    }

    /** As 1.21 and 1.21.1 had it, its attributes already in the properties. */
    public DiggerItem(Tier tier, TagKey<Block> mineable, Item.Properties properties) {
        super(tier, properties);
    }

    /** As 1.21.2 to 1.21.4 had it. */
    public DiggerItem(ToolMaterial material, TagKey<Block> mineable, float attackDamage, float attackSpeed,
            Item.Properties properties) {
        super(null, material.applyToolProperties(properties, mineable, attackDamage, attackSpeed, 0.0F));
    }
}
