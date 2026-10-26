package studios.milkdromeda.octo.compat.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import studios.milkdromeda.octo.compat.mapping.VersionDiff.Member;
import studios.milkdromeda.octo.compat.mapping.VersionDiff.Shape;

/**
 * Renames worked out by comparing two releases' classes, where no table says.
 */
class VersionDiffTest {
    private static final String FURNACE = "net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity";
    private static final String GOAT = "net/minecraft/world/entity/animal/goat/Goat";

    private static Member field(String name, String descriptor) {
        return new Member(name, descriptor, Opcodes.ACC_PRIVATE);
    }

    private static Member method(String name, String descriptor) {
        return new Member(name, descriptor, Opcodes.ACC_PUBLIC);
    }

    private static Shape shape(String name, List<Member> fields, List<Member> methods) {
        return new Shape(name, "java/lang/Object", List.of(), fields, methods);
    }

    private static MappingSet diff() {
        Map<String, Shape> old = Map.of(
                FURNACE, shape(FURNACE,
                        List.of(field("litTime", "I"), field("litDuration", "I"), field("cookingProgress", "I"),
                                field("cookingTotalTime", "I")),
                        List.of(method("getCookingTime", "()I"), method("tick", "()V"))),
                GOAT, shape(GOAT,
                        List.of(new Member("LONG_JUMPING_DIMENSIONS", "Lnet/minecraft/world/entity/EntityDimensions;",
                                Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)),
                        List.of()));
        Map<String, Shape> current = Map.of(
                FURNACE, shape(FURNACE,
                        List.of(field("litTimeRemaining", "I"), field("litTotalTime", "I"), field("cookingTimer", "I"),
                                field("cookingTotalTime", "I")),
                        List.of(method("cookingTime", "()I"), method("tick", "()V"))),
                GOAT, shape(GOAT,
                        List.of(new Member("BABY_DIMENSIONS", "Lnet/minecraft/world/entity/EntityDimensions;",
                                Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)),
                        List.of()));
        return VersionDiff.between(old, current, "test");
    }

    @Test
    @DisplayName("fields renamed in a class are paired by type, order and a shared leading word")
    void renamedFieldsArePaired() {
        MappingSet renames = diff();

        assertEquals("cookingTimer", renames.mapField(FURNACE, "cookingProgress", "I"));
        assertEquals("litTimeRemaining", renames.mapField(FURNACE, "litTime", "I"));
        assertEquals("litTotalTime", renames.mapField(FURNACE, "litDuration", "I"));
        assertEquals("cookingTotalTime", renames.mapField(FURNACE, "cookingTotalTime", "I"),
                "a field both releases have is not a rename");
    }

    @Test
    @DisplayName("a method that became a record accessor is followed")
    void renamedMethodsArePaired() {
        assertEquals("cookingTime", diff().mapMethod(FURNACE, "getCookingTime", "()I"));
    }

    @Test
    @DisplayName("a field removed and an unrelated one added are not mistaken for a rename")
    void unrelatedPairsStayApart() {
        assertEquals("LONG_JUMPING_DIMENSIONS", diff().mapField(GOAT, "LONG_JUMPING_DIMENSIONS",
                "Lnet/minecraft/world/entity/EntityDimensions;"));
    }

    @Test
    @DisplayName("names are related when one contains the other or they lead with the same word")
    void relatedNames() {
        assertTrue(VersionDiff.related("CODEC", "MAP_CODEC"));
        assertTrue(VersionDiff.related("getDescription", "description"));
        assertTrue(VersionDiff.related("getLightBlock", "getLightDampening"));
        assertFalse(VersionDiff.related("LONG_JUMPING_DIMENSIONS", "BABY_DIMENSIONS"));
        assertFalse(VersionDiff.related("getHealth", "getArmor"));
    }

    @Test
    @DisplayName("a name kept for a new return type sends old callers to where the old result went")
    void reusedNamesAreFollowed() {
        String registry = "net/minecraft/core/Registry";
        String id = "(Lnet/minecraft/resources/Identifier;)";
        String key = "(Lnet/minecraft/resources/ResourceKey;)Ljava/lang/Object;";
        // getValue(ResourceKey) is already there, linked by intermediary; only
        // the Identifier one is new.
        Map<String, Shape> old = Map.of(registry, shape(registry, List.of(),
                List.of(method("getValue", key), method("get", id + "Ljava/lang/Object;"),
                        method("tick", "(Lnet/minecraft/world/level/Level;)V"))));
        Map<String, Shape> current = Map.of(registry, shape(registry, List.of(),
                List.of(method("getValue", key), method("get", id + "Ljava/util/Optional;"),
                        method("getValue", id + "Ljava/lang/Object;"),
                        method("tick", "(Lnet/minecraft/server/level/ServerLevel;)V"),
                        method("tickClient", "(Lnet/minecraft/world/level/Level;)V"))));
        MappingSet renames = VersionDiff.between(old, current, "test");

        assertEquals("getValue", renames.mapMethod(registry, "get", id + "Ljava/lang/Object;"));
        assertEquals("tick", renames.mapMethod(registry, "tick", "(Lnet/minecraft/world/level/Level;)V"),
                "a name kept with other arguments is an overload, not a rename");
    }
}
