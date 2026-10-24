package studios.milkdromeda.octo.compat.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;

import studios.milkdromeda.octo.mod.ModFormat;

/** A mod's custom values, read back the way Fabric API and Mod Menu read them. */
class FabricCustomValuesTest {
    private static ModMetadata sodium() {
        return new FabricModMetadata(studios.milkdromeda.octo.mod.ModMetadata.builder("sodium", ModFormat.FABRIC)
                .version("0.8.0")
                .provide("indium")
                .customValue("fabric-renderer-api-v1:contains_renderer", true)
                .customValue("modmenu", Map.of("links", List.of("https://example.org", 3)))
                .customValue("nothing", null)
                .build());
    }

    @Test
    @DisplayName("Fabric API's renderer check sees a mod that ships its own renderer")
    void containsRenderer() {
        ModMetadata metadata = sodium();

        assertTrue(metadata.containsCustomValue("fabric-renderer-api-v1:contains_renderer"));
        assertTrue(metadata.getCustomValue("fabric-renderer-api-v1:contains_renderer").getAsBoolean());
        assertFalse(metadata.containsCustomValue("fabric-renderer-api-v1:disable_ao"));
        assertNull(metadata.getCustomValue("fabric-renderer-api-v1:disable_ao"));
        assertEquals(List.of("indium"), List.copyOf(metadata.getProvides()));
    }

    @Test
    @DisplayName("nested values keep their shape, and the wrong shape throws as Fabric's does")
    void nestedValues() {
        CustomValue modmenu = sodium().getCustomValue("modmenu");

        assertEquals(CustomValue.CvType.OBJECT, modmenu.getType());
        CustomValue.CvArray links = modmenu.getAsObject().get("links").getAsArray();
        assertEquals(2, links.size());
        assertEquals("https://example.org", links.get(0).getAsString());
        assertEquals(3, links.get(1).getAsNumber().intValue());
        assertThrows(ClassCastException.class, () -> links.get(0).getAsNumber());
        assertThrows(ClassCastException.class, modmenu::getAsArray);
        assertEquals(CustomValue.CvType.NULL, sodium().getCustomValue("nothing").getType());
        assertEquals(Arrays.asList("fabric-renderer-api-v1:contains_renderer", "modmenu", "nothing"),
                List.copyOf(sodium().getCustomValues().keySet()));
    }
}
