package studios.milkdromeda.octo.bridge.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The keys mods ask for models by, reduced to the model id they name. */
class ExtraModelsTest {
    @Test
    @DisplayName("a standalone model location and a bare id name the same model")
    void standaloneAndIdAgree() {
        assertEquals("create:block/cogwheel_shaftless", ExtraModels.idOf("create:block/cogwheel_shaftless#standalone"));
        assertEquals("create:block/cogwheel_shaftless", ExtraModels.idOf("create:block/cogwheel_shaftless"));
    }

    @Test
    @DisplayName("an id without a namespace is the game's, and a block state variant is kept")
    void namespaceAndVariant() {
        assertEquals("minecraft:block/stone", ExtraModels.idOf("block/stone"));
        assertEquals("minecraft:lever#face=wall,facing=north,powered=false",
                ExtraModels.idOf("minecraft:lever#face=wall,facing=north,powered=false"));
    }

    @Test
    @DisplayName("nothing asked for is nothing")
    void nothing() {
        assertNull(ExtraModels.idOf(null));
        assertNull(ExtraModels.idOf("#standalone"));
    }
}
