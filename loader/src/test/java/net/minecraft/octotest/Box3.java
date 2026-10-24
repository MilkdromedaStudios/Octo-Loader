package net.minecraft.octotest;

/**
 * Stands in for a game class whose constructor and methods now take the
 * interface rather than the class, as {@code Vec3} does on 26.x.
 */
public final class Box3 {
    public final double x;

    public Box3(Shape3 shape) {
        this.x = shape.x();
    }

    public Box3(double a, double b) {
        this.x = a + b;
    }

    public Shape3 grow(Shape3 by) {
        return new Point3(x + by.x());
    }
}
