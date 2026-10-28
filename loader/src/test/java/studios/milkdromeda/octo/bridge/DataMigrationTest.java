package studios.milkdromeda.octo.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/** Recipe files from 1.21.1, rewritten as this game reads them. */
class DataMigrationTest {
    private static JsonElement json(String text) {
        return JsonParser.parseString(text.replace('\'', '"'));
    }

    private static JsonElement migrate(String text) {
        return DataMigration.recipe(json(text), Set.of("create")::contains);
    }

    @Test
    @DisplayName("old-form item and tag ingredients become ids and #tags, wherever they are")
    void ingredientsAreRewritten() {
        assertEquals(json("{'type':'create:pressing','ingredients':['#c:ingots/iron'],"
                + "'results':[{'id':'create:iron_sheet'}]}"),
                migrate("{'type':'create:pressing','ingredients':[{'tag':'c:ingots/iron'}],"
                        + "'results':[{'id':'create:iron_sheet'}]}"));
        assertEquals(json("{'key':{'A':'create:andesite_alloy','S':'#c:stones'},'pattern':['AS']}"),
                migrate("{'key':{'A':{'item':'create:andesite_alloy'},'S':{'tag':'c:stones'}},'pattern':['AS']}"));
        assertEquals(json("{'ingredients':[['minecraft:dirt','minecraft:grass_block']]}"),
                migrate("{'ingredients':[[{'item':'minecraft:dirt'},{'item':'minecraft:grass_block'}]]}"));
    }

    @Test
    @DisplayName("objects that are not plain ingredients are left as they are")
    void otherObjectsAreKept() {
        String fluid = "{'ingredients':[{'type':'neoforge:tag','amount':250,'tag':'c:milk'}]}";
        assertEquals(json(fluid), migrate(fluid));
        String stack = "{'result':{'item':'minecraft:stone','count':2}}";
        assertEquals(json(stack), migrate(stack));
    }

    @Test
    @DisplayName("a recipe for a mod that is not installed is left out; one whose conditions cannot be told yet is kept")
    void conditions() {
        assertNull(migrate("{'neoforge:conditions':[{'type':'neoforge:mod_loaded','modid':'aether'}],"
                + "'ingredients':[{'item':'aether:aether_dirt'}]}"));
        assertNotNull(migrate("{'neoforge:conditions':[{'type':'neoforge:mod_loaded','modid':'create'}]}"));
        assertNotNull(migrate("{'neoforge:conditions':[{'type':'neoforge:not','value':"
                + "{'type':'neoforge:mod_loaded','modid':'aether'}}]}"));
        assertNull(migrate("{'neoforge:conditions':[{'type':'neoforge:not','value':"
                + "{'type':'neoforge:mod_loaded','modid':'create'}}]}"));
        assertNotNull(migrate("{'neoforge:conditions':[{'type':'neoforge:tag_empty','tag':'c:ores/platinum'}]}"),
                "tags are not loaded yet; the game decides");
    }
}
