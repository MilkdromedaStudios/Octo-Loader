package net.minecraft.world.item;

/**
 * The item with a tier that tools were until 1.21.2, for mods built before
 * then: 26.x makes a tool out of an item's properties and has no class for one.
 */
public class TieredItem extends Item {
    private final Tier tier;

    public TieredItem(Tier tier, Item.Properties properties) {
        super(properties);
        this.tier = tier;
    }

    public Tier getTier() {
        return tier;
    }

    /** A tier, as the material 26.x's item properties take. */
    protected static ToolMaterial material(Tier tier) {
        return (ToolMaterial) studios.milkdromeda.octo.transform.Shims.toolMaterial(tier);
    }
}
