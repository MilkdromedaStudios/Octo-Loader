package studios.milkdromeda.octo.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class BlockLandingsTest {
    /** A mod's block from before 26.x, taking what lands on it. */
    public static class Millstone {
        final List<Object> landed = new ArrayList<>();

        public void updateEntityMovementAfterFallOn(Object getter, Object entity) {
            landed.add(entity);
        }
    }

    /** One that extends it, without overriding the hook. */
    public static class BigMillstone extends Millstone {
    }

    public static class Plain {
    }

    @Test
    void aBlockWithTheOldHookIsToldOfWhatLandsOnIt() {
        Millstone millstone = new BigMillstone();
        BlockLandings.landed(millstone, "level", "item");
        BlockLandings.landed(new Plain(), "level", "item");
        BlockLandings.landed(null, "level", "item");

        assertEquals(List.of("item"), millstone.landed);
    }
}
