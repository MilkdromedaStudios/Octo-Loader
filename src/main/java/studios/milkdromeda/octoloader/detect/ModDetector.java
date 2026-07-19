package studios.milkdromeda.octoloader.detect;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Identifies the ecosystem and self-declared metadata of a mod jar by its
 * marker files. Never throws for malformed jars/metadata — degrades to
 * {@link ModEcosystem#UNKNOWN} or partial info with an explanatory detail.
 */
public final class ModDetector {
    private static final Gson GSON = new Gson();

    public DetectedMod detect(Path jarPath) {
        Map<ModEcosystem, byte[]> markers = new EnumMap<>(ModEcosystem.class);
        List<String> nestedJars = new ArrayList<>();
        String jarVersionFromManifest = null;

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (ModEcosystem eco : ModEcosystem.values()) {
                if (eco.markerFile() == null) continue;
                JarEntry entry = jar.getJarEntry(eco.markerFile());
                if (entry != null) {
                    markers.put(eco, readEntry(jar, entry));
                }
            }
            JarEntry jarJar = jar.getJarEntry("META-INF/jarjar/metadata.json");
            if (jarJar != null) {
                collectJarJarNested(readEntry(jar, jarJar), nestedJars);
            }
            if (jar.getManifest() != null) {
                jarVersionFromManifest = jar.getManifest().getMainAttributes().getValue("Implementation-Version");
            }
        } catch (IOException e) {
            return new DetectedMod(jarPath, ModEcosystem.UNKNOWN, List.of(), null, null, null, null, null,
                    List.of(), "unreadable jar: " + e.getMessage());
        }

        List<ModEcosystem> allMarkers = List.copyOf(markers.keySet());
        // EnumMap iterates in declaration order, which is our detection priority.
        for (Map.Entry<ModEcosystem, byte[]> marker : markers.entrySet()) {
            try {
                DetectedMod result = parse(jarPath, marker.getKey(), marker.getValue(), allMarkers,
                        nestedJars, jarVersionFromManifest);
                if (result != null) return result;
            } catch (RuntimeException e) {
                return new DetectedMod(jarPath, marker.getKey(), allMarkers, null, null, null, null, null,
                        nestedJars, "malformed " + marker.getKey().markerFile() + ": " + e.getMessage());
            }
        }
        return new DetectedMod(jarPath, ModEcosystem.UNKNOWN, allMarkers, null, null, null, null, null,
                nestedJars, "no recognizable mod metadata");
    }

    private DetectedMod parse(Path path, ModEcosystem eco, byte[] data, List<ModEcosystem> allMarkers,
                              List<String> nestedJars, String manifestVersion) {
        String text = new String(data, StandardCharsets.UTF_8);
        return switch (eco) {
            case FABRIC -> parseFabric(path, text, allMarkers, nestedJars);
            case QUILT -> parseQuilt(path, text, allMarkers, nestedJars);
            case NEOFORGE, FORGE -> parseForgeToml(path, eco, text, allMarkers, nestedJars, manifestVersion);
            case FORGE_LEGACY -> parseMcmodInfo(path, text, allMarkers, nestedJars);
            case PAPER_PLUGIN, BUKKIT_PLUGIN -> parsePluginYml(path, eco, text, allMarkers);
            default -> null;
        };
    }

    private DetectedMod parseFabric(Path path, String json, List<ModEcosystem> allMarkers, List<String> nestedJars) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        collectFabricNested(root, nestedJars);
        return new DetectedMod(path, ModEcosystem.FABRIC, allMarkers,
                str(root, "id"),
                str(root, "name"),
                str(root, "version"),
                dependencyRange(root.getAsJsonObject("depends"), "minecraft"),
                dependencyRange(root.getAsJsonObject("depends"), "fabricloader"),
                nestedJars,
                multiLoaderNote(allMarkers));
    }

    private DetectedMod parseQuilt(Path path, String json, List<ModEcosystem> allMarkers, List<String> nestedJars) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject loader = root.getAsJsonObject("quilt_loader");
        if (loader == null) {
            return new DetectedMod(path, ModEcosystem.QUILT, allMarkers, null, null, null, null, null,
                    nestedJars, "quilt.mod.json missing quilt_loader section");
        }
        String name = null;
        JsonObject metadata = loader.getAsJsonObject("metadata");
        if (metadata != null) name = str(metadata, "name");

        String minecraft = null;
        String quiltLoader = null;
        JsonElement depends = loader.get("depends");
        if (depends != null && depends.isJsonArray()) {
            for (JsonElement dep : depends.getAsJsonArray()) {
                String id;
                String versions = "*";
                if (dep.isJsonObject()) {
                    JsonObject obj = dep.getAsJsonObject();
                    id = str(obj, "id");
                    JsonElement v = obj.get("versions");
                    if (v != null) versions = versionsToString(v);
                } else {
                    id = dep.getAsString();
                }
                if ("minecraft".equals(id)) minecraft = versions;
                if ("quilt_loader".equals(id)) quiltLoader = versions;
            }
        }
        JsonElement jars = loader.get("jars");
        if (jars != null && jars.isJsonArray()) {
            for (JsonElement jar : jars.getAsJsonArray()) {
                nestedJars.add(jar.getAsString());
            }
        }
        return new DetectedMod(path, ModEcosystem.QUILT, allMarkers,
                str(loader, "id"), name, str(loader, "version"),
                minecraft, quiltLoader, nestedJars, multiLoaderNote(allMarkers));
    }

    private DetectedMod parseForgeToml(Path path, ModEcosystem eco, String toml, List<ModEcosystem> allMarkers,
                                       List<String> nestedJars, String manifestVersion) {
        Config root = new TomlParser().parse(new StringReader(toml));
        String loaderVersion = root.get("loaderVersion");
        String modLoader = root.get("modLoader");

        String modId = null;
        String displayName = null;
        String version = null;
        List<Config> mods = root.get("mods");
        if (mods != null && !mods.isEmpty()) {
            Config first = mods.getFirst();
            modId = first.get("modId");
            displayName = first.get("displayName");
            version = first.get("version");
        }
        if (version != null && version.contains("${file.jarVersion}")) {
            version = manifestVersion != null ? manifestVersion : "unknown (from jar manifest)";
        }

        String minecraft = null;
        Object dependencies = root.get("dependencies");
        if (dependencies instanceof Config depsConfig && modId != null) {
            List<Config> modDeps = depsConfig.get(List.of(modId));
            if (modDeps != null) {
                for (Config dep : modDeps) {
                    if ("minecraft".equals(dep.get("modId"))) {
                        minecraft = dep.get("versionRange");
                    }
                }
            }
        }

        String detail = multiLoaderNote(allMarkers);
        if (modLoader != null && !"javafml".equals(modLoader)) {
            detail = joinDetail(detail, "modLoader=" + modLoader);
        }
        return new DetectedMod(path, eco, allMarkers, modId, displayName, version,
                minecraft, loaderVersion, nestedJars, detail);
    }

    private DetectedMod parseMcmodInfo(Path path, String json, List<ModEcosystem> allMarkers, List<String> nestedJars) {
        JsonElement root = JsonParser.parseString(json);
        JsonArray modList;
        if (root.isJsonArray()) {
            modList = root.getAsJsonArray();
        } else {
            modList = root.getAsJsonObject().getAsJsonArray("modList");
        }
        if (modList == null || modList.isEmpty()) {
            return new DetectedMod(path, ModEcosystem.FORGE_LEGACY, allMarkers, null, null, null, null, null,
                    nestedJars, "empty mcmod.info");
        }
        JsonObject first = modList.get(0).getAsJsonObject();
        return new DetectedMod(path, ModEcosystem.FORGE_LEGACY, allMarkers,
                str(first, "modid"), str(first, "name"), str(first, "version"),
                str(first, "mcversion"), null, nestedJars, multiLoaderNote(allMarkers));
    }

    @SuppressWarnings("unchecked")
    private DetectedMod parsePluginYml(Path path, ModEcosystem eco, String yamlText, List<ModEcosystem> allMarkers) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object parsed = yaml.load(yamlText);
        if (!(parsed instanceof Map)) {
            return new DetectedMod(path, eco, allMarkers, null, null, null, null, null,
                    List.of(), "malformed " + eco.markerFile());
        }
        Map<String, Object> map = (Map<String, Object>) parsed;
        String apiVersion = map.get("api-version") != null ? String.valueOf(map.get("api-version")) : null;
        return new DetectedMod(path, eco, allMarkers,
                strVal(map.get("name")),
                strVal(map.get("name")),
                strVal(map.get("version")),
                apiVersion != null ? ">=" + apiVersion + " (Bukkit api-version)" : null,
                null, List.of(), multiLoaderNote(allMarkers));
    }

    private void collectFabricNested(JsonObject root, List<String> nestedJars) {
        JsonElement jars = root.get("jars");
        if (jars != null && jars.isJsonArray()) {
            for (JsonElement jar : jars.getAsJsonArray()) {
                if (jar.isJsonObject()) {
                    String file = str(jar.getAsJsonObject(), "file");
                    if (file != null) nestedJars.add(file);
                }
            }
        }
    }

    private void collectJarJarNested(byte[] data, List<String> nestedJars) {
        try {
            JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray jars = root.getAsJsonArray("jars");
            if (jars != null) {
                for (JsonElement jar : jars) {
                    String p = str(jar.getAsJsonObject(), "path");
                    if (p != null) nestedJars.add(p);
                }
            }
        } catch (RuntimeException ignored) {
            // Nested jar listing is informational only.
        }
    }

    private static byte[] readEntry(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static String dependencyRange(JsonObject depends, String id) {
        if (depends == null) return null;
        JsonElement value = depends.get(id);
        return value == null ? null : versionsToString(value);
    }

    private static String versionsToString(JsonElement versions) {
        if (versions.isJsonArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonElement v : versions.getAsJsonArray()) parts.add(v.getAsString());
            return String.join(" || ", parts);
        }
        if (versions.isJsonPrimitive()) return versions.getAsString();
        return versions.toString();
    }

    private static String multiLoaderNote(List<ModEcosystem> allMarkers) {
        if (allMarkers.size() <= 1) return null;
        List<String> names = allMarkers.stream().map(ModEcosystem::displayName).toList();
        return "multi-loader jar (markers: " + String.join(", ", names) + ")";
    }

    private static String joinDetail(String a, String b) {
        if (a == null || a.isBlank()) return b;
        return a + "; " + b;
    }

    private static String str(JsonObject obj, String key) {
        JsonElement value = obj.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String strVal(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
