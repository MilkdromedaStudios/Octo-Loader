package studios.milkdromeda.octo.compat.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A table applied after another must not rename what the first already
 * renamed: 1.21.1's {@code Registry.getHolder(key)} is {@code get(key)} after
 * the published tables, and the jar comparison from 1.21.1 knows {@code
 * get(key)} as the member that became {@code getValue}.
 */
class ChainedRenamesTest {
    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String KEY = "Lnet/minecraft/resources/ResourceKey;";

    private static MappingSet published() {
        return MappingSet.builder("mojang 1.21.1 -> 1.21.11")
                .mapMethod(REGISTRY, "getHolder", "(I)Ljava/util/Optional;", "get")
                .mapMethod(REGISTRY, "get", "(" + KEY + ")Ljava/lang/Object;", "getValue")
                .build();
    }

    private static MappingSet ownRelease() {
        return MappingSet.builder("mojang 1.21.1 -> 26.3")
                .mapMethod(REGISTRY, "get", "(" + KEY + ")Ljava/lang/Object;", "getValue")
                // Listed on the subtype a call names, with a descriptor in another namespace.
                .mapMethod("net/minecraft/core/DefaultedRegistry", "get", "(Lnet/minecraft/resources/Identifier;)"
                        + "Ljava/lang/Object;", "getValue")
                .mapMethod("net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity", "cookingProgress",
                        null, "cookingTimer")
                .mapMethod(REGISTRY, "byNameCodec", null, "byNameCodec2")
                .build();
    }

    @Test
    @DisplayName("an entry for a member the first table renamed is dropped")
    void renamedMembersAreDropped() {
        MappingSet filtered = ownRelease().unchangedBy(published());

        // After the published table, getHolder(key) is get(key); the filtered
        // table must leave that name alone.
        assertEquals("get", filtered.mapMethod(REGISTRY, "get", "(" + KEY + ")Ljava/util/Optional;"));
        assertEquals("get", filtered.mapMethod("net/minecraft/core/DefaultedRegistry", "get",
                "(" + KEY + ")Ljava/util/Optional;"), "a call through a subtype is left alone too");
    }

    @Test
    @DisplayName("an entry for a member the first table lost track of is kept")
    void lostMembersAreKept() {
        MappingSet filtered = ownRelease().unchangedBy(published());

        assertEquals("cookingTimer", filtered.mapMethod(
                "net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity", "cookingProgress", "I"));
        assertEquals("byNameCodec2", filtered.mapMethod(REGISTRY, "byNameCodec", "()V"));
    }
}
