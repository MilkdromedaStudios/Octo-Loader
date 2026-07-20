package studios.milkdromeda.octoloader.translators.quilt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import studios.milkdromeda.octoloader.bytecode.ConstantPool;
import studios.milkdromeda.octoloader.detect.DetectedMod;
import studios.milkdromeda.octoloader.detect.ModEcosystem;
import studios.milkdromeda.octoloader.replace.ApiReplacement;
import studios.milkdromeda.octoloader.replace.ApiRewriter;
import studios.milkdromeda.octoloader.replace.ChainRemapper;
import studios.milkdromeda.octoloader.replace.ReplacementPlan;
import studios.milkdromeda.octoloader.report.CompatStatus;
import studios.milkdromeda.octoloader.report.ModReportEntry;
import studios.milkdromeda.octoloader.translate.TranslationCache;
import studios.milkdromeda.octoloader.translate.JarRepacker;
import studios.milkdromeda.octoloader.translate.TranslationContext;
import studios.milkdromeda.octoloader.translate.Translator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Rewrites a Quilt mod into a Fabric mod. Quilt's formats are documented
 * near-supersets of Fabric's, so for mods that don't use QSL (Quilt's standard
 * library) the translation is a pure metadata rewrite: {@code quilt.mod.json}
 * becomes an equivalent {@code fabric.mod.json}. Class files are untouched for
 * same-version mods; mods built for an older 26.x version additionally go
 * through the API replacement chain, which rewrites their compiled references
 * using the documented per-version changes.
 *
 * <p>Mods that require QSL/Quilted Fabric API modules are flagged
 * {@link CompatStatus#PARTIAL} and skipped — those APIs are not present on a
 * Fabric installation, and pretending otherwise would trade a clean report line
 * for a crash at runtime.
 */
public final class QuiltTranslator implements Translator {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Quilt entrypoint keys → their Fabric equivalents. */
    private static final Map<String, String> ENTRYPOINT_KEYS = Map.of(
            "init", "main",
            "client_init", "client",
            "server_init", "server",
            "pre_launch", "preLaunch"
    );

    @Override
    public String id() {
        return "quilt";
    }

    @Override
    public boolean supports(DetectedMod mod) {
        return mod.ecosystem() == ModEcosystem.QUILT;
    }

    @Override
    public ModReportEntry translate(DetectedMod mod, TranslationContext context) {
        Path output = TranslationCache.outputFor(mod.path(), context.injector().outputDir());

        ReplacementPlan plan = ApiReplacement.plan(mod, context);
        if (plan instanceof ReplacementPlan.Blocked blocked) {
            return new ModReportEntry(mod, CompatStatus.UNSUPPORTED_VERSION, blocked.reason());
        }
        ReplacementPlan.Replace replace = plan instanceof ReplacementPlan.Replace r ? r : null;

        try {
            JsonObject quilt = readQuiltMetadata(mod.path());
            JsonObject loader = quilt.getAsJsonObject("quilt_loader");
            if (loader == null) {
                return new ModReportEntry(mod, CompatStatus.ERROR, "quilt.mod.json has no quilt_loader section");
            }

            List<String> qslModules = requiredQslModules(loader);
            if (!qslModules.isEmpty()) {
                return new ModReportEntry(mod, CompatStatus.PARTIAL,
                        "requires QSL modules Octo Loader does not provide: " + String.join(", ", qslModules));
            }

            Map<String, ConstantPool.ClassRefs> refsByEntry = Map.of();
            if (replace != null) {
                refsByEntry = ConstantPool.scanJar(mod.path());
                List<String> removed = replace.removalBlockers(refsByEntry.values());
                if (!removed.isEmpty()) {
                    return new ModReportEntry(mod, CompatStatus.PARTIAL,
                            "uses APIs the documentation records as removed between " + replace.fromVersion()
                                    + " and " + replace.toVersion() + ": " + String.join(", ", removed));
                }
            }

            if (TranslationCache.isFresh(mod.path(), output, context.runningMinecraft())) {
                return translatedEntry(mod, context, "already translated (cache)");
            }

            JsonObject fabric = toFabricMetadata(quilt, loader);
            if (replace != null) {
                // The mod now runs against the replaced (current) API surface.
                fabric.getAsJsonObject("depends").addProperty("minecraft", context.runningMinecraft());
                fabric.getAsJsonObject("custom").getAsJsonObject("octoloader")
                        .addProperty("apiReplaced", replace.fromVersion() + " -> " + replace.toVersion());
            }

            JarRepacker.ProvenanceStamp stamp = new JarRepacker.ProvenanceStamp(
                    id(), context.runningMinecraft(),
                    replace != null ? replace.fromVersion() + " -> " + replace.toVersion() : null);
            JarRepacker.writeTranslated(mod.path(), output, GSON.toJson(fabric), stamp,
                    classTransform(refsByEntry, replace));

            String note = replace == null ? null
                    : "APIs replaced " + replace.description() + ", "
                    + refsByEntry.values().stream().filter(replace::touches).count() + " class(es) rewritten";
            return translatedEntry(mod, context, note);
        } catch (IOException e) {
            throw new RuntimeException("I/O error translating " + mod.path().getFileName(), e);
        }
    }

    private static BiFunction<String, byte[], byte[]> classTransform(
            Map<String, ConstantPool.ClassRefs> refsByEntry, ReplacementPlan.Replace replace) {
        if (replace == null) return null;
        ChainRemapper remapper = replace.remapper();
        return (entryName, bytes) -> {
            ConstantPool.ClassRefs refs = refsByEntry.get(entryName);
            if (refs == null || !replace.touches(refs)) return bytes;
            return ApiRewriter.rewrite(bytes, remapper);
        };
    }

    private ModReportEntry translatedEntry(DetectedMod mod, TranslationContext context, String prefix) {
        String state = context.loadedModCheck().test(mod.modId())
                ? "active"
                : context.injector().activationNote();
        return new ModReportEntry(mod, CompatStatus.TRANSLATED,
                prefix == null ? state : prefix + "; " + state);
    }

    // ---------------------------------------------------------------- metadata

    private static JsonObject readQuiltMetadata(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry entry = jf.getJarEntry("quilt.mod.json");
            try (InputStream in = jf.getInputStream(entry)) {
                return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }
        }
    }

    /** Required dependencies on QSL / Quilted Fabric API — untranslatable for now. */
    private static List<String> requiredQslModules(JsonObject loader) {
        List<String> modules = new ArrayList<>();
        JsonElement depends = loader.get("depends");
        if (depends == null || !depends.isJsonArray()) return modules;
        for (JsonElement dep : depends.getAsJsonArray()) {
            String id;
            boolean optional = false;
            if (dep.isJsonObject()) {
                JsonObject obj = dep.getAsJsonObject();
                id = str(obj, "id");
                optional = obj.has("optional") && obj.get("optional").getAsBoolean();
            } else {
                id = dep.getAsString();
            }
            if (id == null || optional) continue;
            if (id.equals("qsl") || id.startsWith("qsl_") || id.startsWith("quilted_")
                    || (id.startsWith("quilt_") && !id.equals("quilt_loader"))) {
                modules.add(id);
            }
        }
        return modules;
    }

    static JsonObject toFabricMetadata(JsonObject quilt, JsonObject loader) {
        JsonObject fabric = new JsonObject();
        fabric.addProperty("schemaVersion", 1);
        fabric.addProperty("id", loader.get("id").getAsString());
        fabric.addProperty("version", loader.get("version").getAsString());

        JsonObject metadata = loader.getAsJsonObject("metadata");
        if (metadata != null) {
            copyString(metadata, "name", fabric, "name");
            copyString(metadata, "description", fabric, "description");
            JsonObject contributors = metadata.getAsJsonObject("contributors");
            if (contributors != null) {
                JsonArray authors = new JsonArray();
                for (String author : contributors.keySet()) authors.add(author);
                fabric.add("authors", authors);
            }
            if (metadata.has("contact") && metadata.get("contact").isJsonObject()) {
                fabric.add("contact", metadata.get("contact"));
            }
            copyString(metadata, "license", fabric, "license");
            copyString(metadata, "icon", fabric, "icon");
        }

        JsonObject minecraft = quilt.getAsJsonObject("minecraft");
        if (minecraft != null && minecraft.has("environment")) {
            String env = minecraft.get("environment").getAsString();
            fabric.addProperty("environment", switch (env) {
                case "client" -> "client";
                case "dedicated_server" -> "server";
                default -> "*";
            });
        }

        JsonElement entrypoints = loader.get("entrypoints");
        if (entrypoints != null && entrypoints.isJsonObject()) {
            JsonObject mapped = new JsonObject();
            for (Map.Entry<String, JsonElement> e : entrypoints.getAsJsonObject().entrySet()) {
                String key = ENTRYPOINT_KEYS.getOrDefault(e.getKey(), e.getKey());
                mapped.add(key, toArray(e.getValue()));
            }
            fabric.add("entrypoints", mapped);
        }

        JsonElement mixin = quilt.get("mixin");
        if (mixin != null) {
            fabric.add("mixins", toArray(mixin));
        }
        JsonElement accessWidener = quilt.get("access_widener");
        if (accessWidener != null && accessWidener.isJsonPrimitive()) {
            fabric.addProperty("accessWidener", accessWidener.getAsString());
        }

        fabric.add("depends", mapDependencies(loader.get("depends"), true));
        JsonObject breaks = mapDependencies(loader.get("breaks"), false);
        if (!breaks.isEmpty()) fabric.add("breaks", breaks);

        JsonElement provides = loader.get("provides");
        if (provides != null && provides.isJsonArray()) {
            JsonArray ids = new JsonArray();
            for (JsonElement p : provides.getAsJsonArray()) {
                ids.add(p.isJsonObject() ? p.getAsJsonObject().get("id").getAsString() : p.getAsString());
            }
            if (!ids.isEmpty()) fabric.add("provides", ids);
        }

        JsonObject octo = new JsonObject();
        octo.addProperty("translatedFrom", "quilt");
        JsonObject custom = new JsonObject();
        custom.add("octoloader", octo);
        fabric.add("custom", custom);
        return fabric;
    }

    private static JsonObject mapDependencies(JsonElement quiltDeps, boolean depends) {
        JsonObject result = new JsonObject();
        if (depends) result.addProperty("fabricloader", ">=0.19.0");
        if (quiltDeps == null || !quiltDeps.isJsonArray()) return result;
        for (JsonElement dep : quiltDeps.getAsJsonArray()) {
            String id;
            String versions = "*";
            boolean optional = false;
            if (dep.isJsonObject()) {
                JsonObject obj = dep.getAsJsonObject();
                id = str(obj, "id");
                optional = obj.has("optional") && obj.get("optional").getAsBoolean();
                JsonElement v = obj.get("versions");
                if (v != null && v.isJsonPrimitive()) versions = v.getAsString();
                else if (v != null && v.isJsonArray() && !v.getAsJsonArray().isEmpty()) {
                    versions = v.getAsJsonArray().get(0).getAsString();
                }
            } else {
                id = dep.getAsString();
            }
            if (id == null || optional || id.equals("quilt_loader")) continue;
            // Quilt dep ids may be maven-style "group:name" — Fabric wants bare ids.
            if (id.contains(":")) id = id.substring(id.indexOf(':') + 1);
            result.addProperty(id, versions);
        }
        return result;
    }

    private static JsonArray toArray(JsonElement value) {
        if (value.isJsonArray()) return value.getAsJsonArray();
        JsonArray array = new JsonArray();
        array.add(value);
        return array;
    }

    private static void copyString(JsonObject from, String fromKey, JsonObject to, String toKey) {
        JsonElement value = from.get(fromKey);
        if (value != null && value.isJsonPrimitive()) to.addProperty(toKey, value.getAsString());
    }

    private static String str(JsonObject obj, String key) {
        JsonElement value = obj.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
