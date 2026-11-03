package studios.milkdromeda.octo.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LegacyClientAssetsTest {
    @Test
    @DisplayName("Mod item models with no item definition get one that draws them; real definitions win")
    void itemDefinitionsForOlderItemModels() {
        List<LegacyClientAssets.Definition> made = LegacyClientAssets.missing(List.of(
                new String[] { "create", "models/item/cogwheel.json" },
                new String[] { "create", "models/item/andesite_alloy.json" },
                new String[] { "create", "models/item/sub/part.json" },
                new String[] { "newmod", "models/item/gem.json" },
                new String[] { "minecraft", "models/item/stick.json" },
                new String[] { "create", "models/item/notes.txt" }),
                Set.of("newmod:items/gem.json"));

        assertEquals(List.of("create:items/cogwheel.json", "create:items/andesite_alloy.json",
                "create:items/sub/part.json"),
                made.stream().map(d -> d.namespace() + ":" + d.itemsPath()).toList(),
                "the game's own items and a mod's own definitions are left alone");
        assertEquals("create:item/cogwheel", made.get(0).model());
        assertEquals("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"create:item/cogwheel\"}}",
                new String(made.get(0).json(), StandardCharsets.UTF_8), "as the game writes its own");
    }

    @Test
    @DisplayName("A block item takes its block's name, and tooltip lines, where its own key has none")
    void blockItemNames() {
        Map<String, String> lang = Map.of(
                "block.create.cogwheel", "Cogwheel",
                "block.create.cogwheel.tooltip.summary", "Turns things",
                "block.create.shaft", "Shaft",
                "item.create.shaft", "Shaft Item",
                "block.minecraft.stone", "Stone",
                "item.create.wrench", "Wrench");
        Map<String, String> out = LegacyClientAssets.withBlockItemNames(lang);

        assertEquals("Cogwheel", out.get("item.create.cogwheel"));
        assertEquals("Turns things", out.get("item.create.cogwheel.tooltip.summary"));
        assertEquals("Shaft Item", out.get("item.create.shaft"), "a mod's own item name stays");
        assertEquals(null, out.get("item.minecraft.stone"), "the game's own names are its business");
        assertEquals(lang.size() + 2, out.size());

        Map<String, String> nothingToDo = Map.of("item.create.wrench", "Wrench");
        assertSame(nothingToDo, LegacyClientAssets.withBlockItemNames(nothingToDo));
    }
}
