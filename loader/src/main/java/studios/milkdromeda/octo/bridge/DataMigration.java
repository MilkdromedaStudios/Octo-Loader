package studios.milkdromeda.octo.bridge;

import java.util.Map;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * A translated mod's recipe files, in the format this game reads.
 *
 * <p>1.21.2 changed how an ingredient is written: {@code {"item": "x"}} became
 * {@code "x"}, {@code {"tag": "t"}} became {@code "#t"}, and a list of items
 * a list of their ids. Every recipe a mod wrote before then is in the old
 * form, and this game cannot read one of them — and stops loading data
 * altogether at the first it cannot read. They are rewritten as they are read;
 * the mod's jar is left as it is.
 *
 * <p>NeoForge's load conditions are honoured where they can be told now: a
 * recipe for when another mod is installed is left out when it is not, as
 * NeoForge itself would. Without NeoForge's patches nothing else reads them.
 */
public final class DataMigration {
    private DataMigration() {
    }

    /**
     * @param modLoaded whether a mod of that id is installed
     * @return the recipe as this game reads it, or {@code null} when its
     *         conditions say it is not to be loaded
     */
    public static JsonElement recipe(JsonElement recipe, Predicate<String> modLoaded) {
        if (recipe instanceof JsonObject object) {
            for (String key : new String[] { "neoforge:conditions", "conditions", "forge:conditions" }) {
                if (object.get(key) instanceof JsonArray conditions && !allHold(conditions, modLoaded)) {
                    return null;
                }
            }
        }

        return ingredients(recipe);
    }

    /**
     * An advancement as this game reads it, or {@code null} when its
     * conditions say it is not to be loaded.
     *
     * <p>Every recipe a mod adds comes with an advancement that unlocks it,
     * one criterion asking whether the recipe is known already, under the
     * {@code recipe} key 26.x no longer reads. Left as it was, each of those
     * thousands failed to load, Create's alone 1,048 of them.
     */
    public static JsonElement advancement(JsonElement advancement, Predicate<String> modLoaded) {
        if (!(advancement instanceof JsonObject object)) {
            return advancement;
        }

        for (String key : new String[] { "neoforge:conditions", "conditions", "forge:conditions" }) {
            if (object.get(key) instanceof JsonArray conditions && !allHold(conditions, modLoaded)) {
                return null;
            }
        }

        if (object.get("criteria") instanceof JsonObject criteria) {
            for (var entry : criteria.entrySet()) {
                if (entry.getValue() instanceof JsonObject criterion
                        && "minecraft:recipe_unlocked".equals(string(criterion.get("trigger")))
                        && criterion.get("conditions") instanceof JsonObject conditions
                        && conditions.has("recipe") && !conditions.has("recipes")) {
                    // Never met, rather than naming the recipe: 26.x holds
                    // recipes in a registry, and a name for one this game
                    // left out is a reference that stops the server. The
                    // criterion only asks whether the recipe is known already;
                    // the item beside it is what unlocks it.
                    criterion.remove("conditions");
                    criterion.addProperty("trigger", "minecraft:impossible");
                }
            }
        }

        return object;
    }

    private static String string(JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static boolean allHold(JsonArray conditions, Predicate<String> modLoaded) {
        for (JsonElement condition : conditions) {
            if (Boolean.FALSE.equals(holds(condition, modLoaded))) {
                return false;
            }
        }

        return true;
    }

    /** @return whether the condition holds, or {@code null} when that cannot be told before the game has loaded */
    private static Boolean holds(JsonElement condition, Predicate<String> modLoaded) {
        if (!(condition instanceof JsonObject object) || !(object.get("type") instanceof JsonPrimitive type)) {
            return null;
        }

        String name = type.getAsString();
        name = name.substring(name.indexOf(':') + 1);

        return switch (name) {
            case "mod_loaded" -> object.get("modid") instanceof JsonPrimitive modid
                    ? modLoaded.test(modid.getAsString()) : null;
            case "not" -> {
                Boolean inner = holds(object.get("value"), modLoaded);
                yield inner == null ? null : !inner;
            }
            case "and" -> object.get("values") instanceof JsonArray values ? allOf(values, modLoaded) : null;
            case "or" -> object.get("values") instanceof JsonArray values ? anyOf(values, modLoaded) : null;
            case "true" -> true;
            case "false" -> false;
            default -> null;
        };
    }

    private static Boolean allOf(JsonArray values, Predicate<String> modLoaded) {
        boolean unknown = false;

        for (JsonElement value : values) {
            Boolean result = holds(value, modLoaded);

            if (Boolean.FALSE.equals(result)) {
                return false;
            }

            unknown |= result == null;
        }

        return unknown ? null : true;
    }

    private static Boolean anyOf(JsonArray values, Predicate<String> modLoaded) {
        boolean unknown = false;

        for (JsonElement value : values) {
            Boolean result = holds(value, modLoaded);

            if (Boolean.TRUE.equals(result)) {
                return true;
            }

            unknown |= result == null;
        }

        return unknown ? null : false;
    }

    /**
     * A translated mod's tag file, with every entry it adds made optional.
     *
     * <p>The game refuses a whole tag when one entry names nothing, and a tag
     * other tags include takes them down with it. A mod from another version
     * often has a block or two this game could not register: Farmer's
     * Delight's rope fence gate missing from {@code minecraft:fence_gates}
     * took {@code blocks_motion}, every tag built on it, and then the
     * enchantments asking for those, and the server would not start. Optional,
     * the missing entry is left out and the rest of the tag stands.
     */
    public static JsonElement tag(JsonElement tag) {
        if (!(tag instanceof JsonObject object) || !(object.get("values") instanceof JsonArray values)) {
            return tag;
        }

        JsonArray optional = new JsonArray();

        for (JsonElement value : values) {
            if (value instanceof JsonPrimitive id && id.isString()) {
                JsonObject entry = new JsonObject();
                entry.add("id", id);
                entry.addProperty("required", false);
                optional.add(entry);
            } else if (value instanceof JsonObject entry && entry.has("id")) {
                JsonObject copy = entry.deepCopy();
                copy.addProperty("required", false);
                optional.add(copy);
            } else {
                optional.add(value);
            }
        }

        JsonObject out = object.deepCopy();
        out.add("values", optional);
        return out;
    }

    /** Every old-form ingredient in the tree, written the new way. */
    /**
     * A fluid ingredient as NeoForge wrote it until 26.x — {@code {"type":
     * "neoforge:single", "fluid": ..., "amount": 250}}, {@code "neoforge:tag"}
     * with a {@code "tag"}, or a compound of single fluids — as 26.x reads one
     * in a map: the
     * components ingredient over that fluid or tag, which asks nothing of
     * components unless told. The amount stays beside it, where the recipe's
     * own codec reads it. Every Create recipe that takes a fluid was left out
     * without it: Steam 'n' Rails' 1,063 mixing recipes, chocolate, honey.
     *
     * @return the rewritten ingredient, or {@code null} when it is not one
     */
    private static JsonElement fluidIngredient(JsonObject object) {
        String type = string(object.get("type"));
        JsonElement fluids;

        if ("neoforge:single".equals(type) && string(object.get("fluid")) != null) {
            fluids = new JsonPrimitive(string(object.get("fluid")));
        } else if ("neoforge:tag".equals(type) && string(object.get("tag")) != null) {
            fluids = new JsonPrimitive("#" + string(object.get("tag")));
        } else if ("neoforge:compound".equals(type) && object.get("ingredients") instanceof JsonArray children) {
            // Several fluids: one set of them, as long as each is a plain fluid.
            JsonArray ids = new JsonArray();

            for (JsonElement child : children) {
                String id = child instanceof JsonObject single ? string(single.get("fluid")) : string(child);

                if (id == null) {
                    return null;
                }

                ids.add(id);
            }

            fluids = ids;
        } else {
            return null;
        }

        JsonObject out = new JsonObject();
        out.addProperty("neoforge:ingredient_type", "neoforge:components");
        out.add("fluids", fluids);
        out.add("components", new JsonObject());
        out.addProperty("strict", false);

        if (object.has("amount")) {
            out.add("amount", object.get("amount"));
        }

        return out;
    }

    static JsonElement ingredients(JsonElement element) {
        if (element instanceof JsonObject object) {
            if (object.size() == 1 && object.get("item") instanceof JsonPrimitive item && item.isString()) {
                return item;
            }

            if (object.size() == 1 && object.get("tag") instanceof JsonPrimitive tag && tag.isString()) {
                return new JsonPrimitive("#" + tag.getAsString());
            }

            JsonElement fluid = fluidIngredient(object);

            if (fluid != null) {
                return fluid;
            }

            JsonObject out = new JsonObject();

            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                out.add(entry.getKey(), ingredients(entry.getValue()));
            }

            return out;
        }

        if (element instanceof JsonArray array) {
            JsonArray out = new JsonArray();

            for (JsonElement value : array) {
                out.add(ingredients(value));
            }

            return out;
        }

        return element;
    }
}
