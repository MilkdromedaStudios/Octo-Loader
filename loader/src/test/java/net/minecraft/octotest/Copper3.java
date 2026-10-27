package net.minecraft.octotest;

import net.minecraft.world.level.block.WeatheringCopperCollection;
import net.minecraft.world.level.block.WeatheringCopperCollection.ByState;

/** Blocks the way 26.x keeps its copper: one collection where there were eight fields. */
public final class Copper3 {
    public static final WeatheringCopperCollection<String> COPPER_BLOCK = of("copper");
    public static final WeatheringCopperCollection<String> CUT_COPPER = of("cut copper");

    private Copper3() {
    }

    private static WeatheringCopperCollection<String> of(String name) {
        return new WeatheringCopperCollection<>(
                new ByState<>(name, "exposed " + name, "weathered " + name, "oxidized " + name),
                new ByState<>("waxed " + name, "waxed exposed " + name, "waxed weathered " + name,
                        "waxed oxidized " + name));
    }
}
