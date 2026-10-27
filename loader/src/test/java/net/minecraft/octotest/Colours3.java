package net.minecraft.octotest;

import net.minecraft.world.level.block.ColorCollection;

/** Blocks the way 26.x keeps its dyed ones: one collection where there were sixteen fields. */
public final class Colours3 {
    public static final ColorCollection<String> WOOL = new ColorCollection<>("yellow wool", "light blue wool",
            "red wool");
    public static final ColorCollection<String> DYED_SHULKER_BOX = new ColorCollection<>("yellow box",
            "light blue box", "red box");

    private Colours3() {
    }
}
