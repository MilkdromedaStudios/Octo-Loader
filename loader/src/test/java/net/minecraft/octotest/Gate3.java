package net.minecraft.octotest;

/**
 * A game class whose constructors changed: it takes its wood where it took
 * sounds, as 26.x's {@code FenceGateBlock} does, and its name after its tag
 * where it took them the other way round, as {@code StandingAndWallBlockItem}
 * now takes its direction before its properties.
 */
public class Gate3 {
    public final String wood;
    public final String name;
    public final Tag3 tag;

    public Gate3(Wood3 wood) {
        this.wood = wood.name;
        this.name = null;
        this.tag = null;
    }

    public Gate3(Tag3 tag, String name) {
        this.wood = null;
        this.name = name;
        this.tag = tag;
    }
}
