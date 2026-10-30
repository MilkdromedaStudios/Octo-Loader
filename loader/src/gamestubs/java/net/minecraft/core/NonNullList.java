package net.minecraft.core;

/** Compile-time shape of the game's non-null list. */
public class NonNullList<E> extends java.util.AbstractList<E> {
    public static native <E> NonNullList<E> withSize(int size, E fill);

    @Override
    public native E get(int index);

    @Override
    public native E set(int index, E value);

    @Override
    public native int size();
}
