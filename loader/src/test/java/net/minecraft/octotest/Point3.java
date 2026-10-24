package net.minecraft.octotest;

/** Stands in for the concrete type older mods passed, as JOML's Vector3f. */
public final class Point3 implements Shape3 {
    private final double x;

    public Point3(double x) {
        this.x = x;
    }

    @Override
    public double x() {
        return x;
    }
}
