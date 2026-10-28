package net.minecraft.octotest;

/** Stands in for {@code BlockBehaviour.Properties}: a builder whose "is" setters changed their parameter. */
public final class Builder3 {
    public int calls;

    public Builder3 strength(float value) {
        calls++;
        return this;
    }
}
