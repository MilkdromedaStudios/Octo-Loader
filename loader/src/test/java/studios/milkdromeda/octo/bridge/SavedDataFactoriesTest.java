package studios.milkdromeda.octo.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SavedDataFactoriesTest {
    @Test
    @DisplayName("an old saved data name becomes an id the game accepts")
    void namesBecomeIds() {
        assertEquals("minecraft:createaddition_networks", SavedDataFactories.idOf("createaddition_networks"));
        assertEquals("create:schematics", SavedDataFactories.idOf("create:schematics"));
        assertEquals("minecraft:my_mod_data_v2", SavedDataFactories.idOf("My Mod Data V2"));
        assertEquals("minecraft:data", SavedDataFactories.idOf(""));
    }
}
