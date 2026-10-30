package net.minecraft.octotest;

/**
 * Stands in for a block state after 1.21.2, which works out its own shapes
 * and light: {@code light(side)} where older code called
 * {@code light(level, pos, side)}.
 */
public final class State3 {
    public double light(Shape3 side) {
        return side.x() * 10;
    }
}
