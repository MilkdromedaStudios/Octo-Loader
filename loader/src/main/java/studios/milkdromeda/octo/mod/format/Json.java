package studios.milkdromeda.octo.mod.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Lenient JSON helpers.
 *
 * <p>Mod metadata in the wild has trailing commas, comments and fields that are
 * "a string, or a list of strings, or a list of objects". Gson in lenient mode
 * plus these accessors handle all of it without a parser per format.
 */
final class Json {
    private Json() {
    }

    static JsonElement parse(byte[] bytes) throws MetadataException {
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);

            // Strip a UTF-8 BOM; a surprising number of mcmod.info files have one.
            if (!text.isEmpty() && text.charAt(0) == '﻿') {
                text = text.substring(1);
            }

            return JsonParser.parseString(text);
        } catch (RuntimeException e) {
            throw new MetadataException("malformed JSON: " + e.getMessage(), e);
        }
    }

    static String string(JsonObject object, String key, String fallback) {
        JsonElement element = object == null ? null : object.get(key);

        if (element == null || element.isJsonNull()) {
            return fallback;
        }

        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }

        // {"name": "..."} shapes, used for licences and contributors.
        if (element.isJsonObject()) {
            JsonObject nested = element.getAsJsonObject();

            for (String candidate : new String[] { "name", "id", "value" }) {
                if (nested.has(candidate) && nested.get(candidate).isJsonPrimitive()) {
                    return nested.get(candidate).getAsString();
                }
            }
        }

        if (element.isJsonArray() && !element.getAsJsonArray().isEmpty()) {
            return element.getAsJsonArray().get(0).getAsString();
        }

        return fallback;
    }

    static JsonObject object(JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }

        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** Reads a field that may be a single value or an array of them. */
    static List<JsonElement> list(JsonObject parent, String key) {
        List<JsonElement> out = new ArrayList<>();

        if (parent == null) {
            return out;
        }

        JsonElement element = parent.get(key);

        if (element == null || element.isJsonNull()) {
            return out;
        }

        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                out.add(child);
            }
        } else {
            out.add(element);
        }

        return out;
    }

    static List<String> strings(JsonObject parent, String key) {
        List<String> out = new ArrayList<>();

        for (JsonElement element : list(parent, key)) {
            if (element.isJsonPrimitive()) {
                out.add(element.getAsString());
            } else if (element.isJsonObject()) {
                String name = string(element.getAsJsonObject(), "name", null);

                if (name != null) {
                    out.add(name);
                }
            }
        }

        return out;
    }

    /** {@code "value"} or {@code {"value": "..."}} — the shape Fabric uses for entrypoints and mixins. */
    static String valueOf(JsonElement element, String key) {
        if (element == null || element.isJsonNull()) {
            return null;
        }

        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }

        if (element.isJsonObject()) {
            JsonElement nested = element.getAsJsonObject().get(key);

            if (nested != null && nested.isJsonPrimitive()) {
                return nested.getAsString();
            }
        }

        return null;
    }

    static JsonArray array(JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    static boolean isTrue(JsonObject object, String key, boolean fallback) {
        JsonElement element = object == null ? null : object.get(key);

        if (element instanceof JsonPrimitive primitive) {
            return primitive.isBoolean() ? primitive.getAsBoolean() : Boolean.parseBoolean(primitive.getAsString());
        }

        return fallback;
    }
}
