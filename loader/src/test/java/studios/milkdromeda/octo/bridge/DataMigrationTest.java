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

    @Test
    @DisplayName("a translated mod's tag entries are made optional, so one missing entry leaves the rest standing")
    void tagEntriesBecomeOptional() {
        assertEquals(json("{'replace':false,'values':[{'id':'farmersdelight:rope_fence_gate','required':false},"
                + "{'id':'#c:fence_gates','required':false},{'id':'minecraft:oak_fence_gate','required':false}]}"),
                DataMigration.tag(json("{'replace':false,'values':['farmersdelight:rope_fence_gate','#c:fence_gates',"
                        + "{'id':'minecraft:oak_fence_gate','required':true}]}")));
        assertEquals(json("{'not':'a tag'}"), DataMigration.tag(json("{'not':'a tag'}")),
                "a file with no values is left as it is");
    }

    @Test
    @DisplayName("an old fluid ingredient becomes the components ingredient over the same fluid or tag")
    void fluidIngredientsAreRewritten() {
        assertEquals(json("{'type':'create:compacting','ingredients':[{'neoforge:ingredient_type':'neoforge:components',"
                + "'fluids':'create:chocolate','components':{},'strict':false,'amount':250}]}"),
                migrate("{'type':'create:compacting','ingredients':[{'type':'neoforge:single','amount':250,"
                        + "'fluid':'create:chocolate'}]}"));
        assertEquals(json("{'ingredients':['minecraft:glass_bottle',{'neoforge:ingredient_type':'neoforge:components',"
                + "'fluids':'#c:honey','components':{},'strict':false,'amount':250}]}"),
                migrate("{'ingredients':[{'item':'minecraft:glass_bottle'},{'type':'neoforge:tag','amount':250,"
                        + "'tag':'c:honey'}]}"));
        assertEquals(json("{'ingredients':[{'neoforge:ingredient_type':'neoforge:components','fluids':['a:blue','a:red'],"
                + "'components':{},'strict':false,'amount':1000}]}"),
                migrate("{'ingredients':[{'type':'neoforge:compound','amount':1000,"
                        + "'ingredients':[{'fluid':'a:blue'},{'fluid':'a:red'}]}]}"));
    }

    @Test
    @DisplayName("a recipe advancement's already-known criterion no longer names the recipe")
    void recipeAdvancementIsRewritten() {
        assertEquals(json("{'criteria':{'has_the_recipe':{'trigger':'minecraft:impossible'}}}"),
                DataMigration.advancement(json("{'criteria':{'has_the_recipe':{'trigger':'minecraft:recipe_unlocked',"
                        + "'conditions':{'recipe':'create:acacia_window'}}}}"), Set.of("create")::contains));
        assertNull(DataMigration.advancement(json("{'neoforge:conditions':[{'type':'neoforge:mod_loaded',"
                + "'modid':'absent'}],'criteria':{}}"), Set.of("create")::contains));
    }
}
