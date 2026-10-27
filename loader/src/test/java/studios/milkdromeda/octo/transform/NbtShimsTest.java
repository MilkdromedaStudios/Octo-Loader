package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;

/** The NBT helpers 1.21.5 dropped, answered as they were from what the game has now. */
class NbtShimsTest {
    @Test
    @DisplayName("each removed NBT helper has a shim")
    void shimsAreFound() {
        assertNotNull(Shims.shimFor("net/minecraft/nbt/NbtUtils", "writeBlockPos",
                "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/nbt/Tag;"));
        assertNotNull(Shims.shimFor("net/minecraft/nbt/CompoundTag", "putUUID", "(Ljava/lang/String;Ljava/util/UUID;)V"));
        assertNotNull(Shims.shimFor("net/minecraft/nbt/Tag", "getAsString", "()Ljava/lang/String;"));
    }

    @Test
    @DisplayName("a block position written as three ints reads back")
    void blockPositionRoundTrips() {
        Object written = Shims.nbtWriteBlockPos(new BlockPos(1, -60, 3), CompoundTag.class);
        assertArrayEquals(new int[] { 1, -60, 3 }, ((IntArrayTag) written).getAsIntArray());

        CompoundTag tag = new CompoundTag();
        tag.putIntArray("Source", ((IntArrayTag) written).getAsIntArray());

        assertEquals(Optional.of(new BlockPos(1, -60, 3)), Shims.nbtReadBlockPos(tag, "Source", CompoundTag.class));
        assertEquals(Optional.empty(), Shims.nbtReadBlockPos(tag, "Missing", CompoundTag.class));
    }

    @Test
    @DisplayName("a UUID put in a compound reads back, and one never put is absent")
    void uuidRoundTrips() {
        UUID uuid = UUID.randomUUID();
        CompoundTag tag = new CompoundTag();
        Shims.compoundPutUuid(tag, "Owner", uuid);

        assertTrue(Shims.compoundHasUuid(tag, "Owner"));
        assertEquals(uuid, Shims.compoundGetUuid(tag, "Owner"));
        assertFalse(Shims.compoundHasUuid(tag, "Nobody"));
        assertNull(Shims.compoundGetUuid(tag, "Nobody"));
        assertEquals(uuid, Shims.nbtLoadUuid(Shims.nbtCreateUuid(uuid, CompoundTag.class), CompoundTag.class));
    }

    @Test
    @DisplayName("a list of ints is put as an int array")
    void intListIsPut() {
        CompoundTag tag = new CompoundTag();
        Shims.compoundPutIntList(tag, "Ids", List.of(4, 5, 6));

        assertArrayEquals(new int[] { 4, 5, 6 }, tag.getIntArray("Ids").orElseThrow());
    }
}
