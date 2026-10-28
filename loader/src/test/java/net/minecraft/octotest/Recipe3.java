package net.minecraft.octotest;

/** A game interface that gained abstract methods after a mod implemented it. */
public interface Recipe3 {
    String assemble(String input);

    String group();

    boolean showNotification();

    int count();
}
