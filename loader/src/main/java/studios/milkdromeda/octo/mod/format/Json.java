package studios.milkdromeda.octo.mod.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * An icon field as Fabric and Quilt write it: a path, or paths keyed by
     * their pixel size, of which the largest is the one worth showing.
     */
    static String icon(JsonObject object, String key) {
        if (object == null || !object.has(key)) {
            return null;
        }

        com.google.gson.JsonElement value = object.get(key);

        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }

        if (!value.isJsonObject()) {
            return null;
        }

        String best = null;
        int bestSize = -1;

        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : value.getAsJsonObject().entrySet()) {
            int size;

            try {
                size = Integer.parseInt(entry.getKey().trim());
            } catch (NumberFormatException e) {
                size = 0;
            }

            if (size > bestSize && entry.getValue().isJsonPrimitive()) {
                best = entry.getValue().getAsString();
                bestSize = size;
            }
        }

        return best;
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

    /** A JSON value as plain Java: maps, lists, strings, numbers, booleans and null. */
    static Object plain(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }

        if (element instanceof JsonPrimitive primitive) {
            return primitive.isBoolean() ? primitive.getAsBoolean()
                    : primitive.isNumber() ? primitive.getAsNumber() : primitive.getAsString();
        }

        if (element.isJsonArray()) {
            List<Object> out = new ArrayList<>();
            element.getAsJsonArray().forEach(child -> out.add(plain(child)));
            return Collections.unmodifiableList(out);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        element.getAsJsonObject().entrySet().forEach(entry -> out.put(entry.getKey(), plain(entry.getValue())));
        return Collections.unmodifiableMap(out);
    }
}
