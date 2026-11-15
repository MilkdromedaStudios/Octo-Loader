package net.minecraft.world.item;

import net.minecraft.tags.BlockTags;

/** The pickaxe class mods extended until 1.21.5. */
public class PickaxeItem extends DiggerItem {
    public PickaxeItem(Tier tier, int attackDamage, float attackSpeed, Item.Properties properties) {
        super((float) attackDamage, attackSpeed, tier, BlockTags.MINEABLE_WITH_PICKAXE, properties);
    }

    public PickaxeItem(Tier tier, Item.Properties properties) {
        super(tier, BlockTags.MINEABLE_WITH_PICKAXE, properties);
    }

    public PickaxeItem(ToolMaterial material, float attackDamage, float attackSpeed, Item.Properties properties) {
        super(material, BlockTags.MINEABLE_WITH_PICKAXE, attackDamage, attackSpeed, properties);
    }
}
