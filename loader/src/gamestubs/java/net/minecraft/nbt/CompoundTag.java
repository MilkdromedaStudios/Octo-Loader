package net.minecraft.nbt;

/** Compile-time shape of the game's compound tag; members as 26.x has them. */
public final class CompoundTag implements Tag {
    public CompoundTag() {
    }

    public native Tag put(String key, Tag value);

    public native void putInt(String key, int value);

    public native int getIntOr(String key, int fallback);

    public native boolean contains(String key);

    public native CompoundTag getCompoundOrEmpty(String key);

    public native ListTag getListOrEmpty(String key);
}
