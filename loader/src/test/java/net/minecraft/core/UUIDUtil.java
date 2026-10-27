package net.minecraft.core;

import java.util.UUID;

/** The game's UUID helpers: a UUID as four ints, most significant first. */
public final class UUIDUtil {
    private UUIDUtil() {
    }

    public static int[] uuidToIntArray(UUID uuid) {
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        return new int[] { (int) (most >> 32), (int) most, (int) (least >> 32), (int) least };
    }

    public static UUID uuidFromIntArray(int[] ints) {
        return new UUID((long) ints[0] << 32 | ints[1] & 0xFFFFFFFFL, (long) ints[2] << 32 | ints[3] & 0xFFFFFFFFL);
    }
}
