package net.minecraftforge.common;

import java.util.function.Supplier;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

/**
 * Forge's spawn egg for an entity type that is registered after it is named,
 * which Forge dropped once the game's own egg could be told its type late.
 * Built when items are registered, by which time the entity types are in: the
 * type goes into the egg's properties, where 26.x keeps it. The two colours
 * are taken and not used; an egg's look is its model's since 1.21.5.
 */
public class ForgeSpawnEggItem extends SpawnEggItem {
    private final Supplier<? extends EntityType<? extends Mob>> type;

    public ForgeSpawnEggItem(Supplier<? extends EntityType<? extends Mob>> type, int backgroundColor,
            int highlightColor, Item.Properties properties) {
        super(properties.spawnEgg(type.get()));
        this.type = type;
    }

    public EntityType<?> getType() {
        return type.get();
    }
}
