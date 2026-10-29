package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Classes a mod names that moved, found where they went. */
class ClassRelinkerTest {
    @Test
    @DisplayName("a class that moved package is found by its simple name")
    void movedClassIsFound() {
        Set<String> present = Set.of("net/minecraft/client/renderer/block/dispatch/BlockStateModel");
        ClassRelinker relinker = new ClassRelinker(present::contains, Map.of("BlockStateModel",
                List.of("net/minecraft/client/renderer/block/dispatch/BlockStateModel")));

        assertEquals("net/minecraft/client/renderer/block/dispatch/BlockStateModel",
                relinker.replacementFor("net/minecraft/client/renderer/block/model/BlockStateModel"));
        assertNull(relinker.replacementFor("net/minecraft/client/renderer/block/dispatch/BlockStateModel"),
                "a class that is there is left alone");
    }

    @Test
    @DisplayName("once relinked, a name stays relinked even if the old class is added back later")
    void decisionsAreStable() {
        String old = "net/minecraft/client/renderer/block/model/BlockStateModel";
        String moved = "net/minecraft/client/renderer/block/dispatch/BlockStateModel";
        Set<String> present = new HashSet<>(Set.of(moved));
        ClassRelinker relinker = new ClassRelinker(present::contains, Map.of("BlockStateModel", List.of(moved)));

        assertEquals(moved, relinker.replacementFor(old));

        // Another mod's class needed the old one, and it was added back from the old release.
        present.add(old);

        assertEquals(moved, relinker.replacementFor(old),
                "classes loaded later must name the same class as the ones loaded first");
    }
}
