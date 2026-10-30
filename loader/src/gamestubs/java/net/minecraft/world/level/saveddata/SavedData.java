package net.minecraft.world.level.saveddata;

/** Compile-time shape of the game's saved data; members as 26.x has them. */
public abstract class SavedData {
    public native void setDirty();

    public native boolean isDirty();
}
