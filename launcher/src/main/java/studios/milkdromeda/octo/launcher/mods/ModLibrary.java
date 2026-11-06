package studios.milkdromeda.octo.launcher.mods;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

/**
 * The mods folder, as the mod manager sees it: what is in it, read from each
 * jar's own metadata; turned on and off by renaming to {@code .disabled},
 * which no loader reads; and what came from Modrinth, kept in
 * {@code <game>/octo/mods.json} so updates can be found.
 */
public final class ModLibrary {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DISABLED = ".disabled";

    /** One mod file. */
    public record InstalledMod(Path file, String id, String name, String version, String loader, String description,
            byte[] icon, boolean enabled, String projectId) {
        public String fileName() {
            return file.getFileName().toString();
        }
    }

    /** Where a file came from on Modrinth. */
    public record Source(String projectId, String versionId, String title) {
    }

    /** What one install did. */
    public record Installed(List<String> added, List<String> alreadyThere) {
    }

    private final Path game;
    /** For a plain game: the one loader its mods must be for, as Modrinth names it. */
    private final String onlyLoader;

    public ModLibrary(Path game) {
        this(game, null);
    }

    /**
     * @param onlyLoader for a game that runs one loader plainly, without Octo:
     *                   only that loader's builds of mods, for exactly the game's
     *                   version, are installed ("vanilla" takes none); {@code null}
     *                   for Octo, which runs any loader's and translates versions
     */
    public ModLibrary(Path game, String onlyLoader) {
        this.game = game;
        this.onlyLoader = onlyLoader;
    }

    /**
     * The build of a mod to install: with Octo the best of all, for a plain
     * game the newest for its loader and its exact Minecraft version.
     */
    Modrinth.Version choose(List<Modrinth.Version> versions, String gameVersion) {
        if (onlyLoader == null) {
            return Modrinth.best(versions, gameVersion);
        }

        return versions.stream()
                .filter(version -> version.loaders().contains(onlyLoader) && version.gameVersions().contains(gameVersion))
                .max(java.util.Comparator.comparing(Modrinth.Version::published)).orElse(null);
    }

    public Path folder() {
        return game.resolve("mods");
    }

    private Path sourcesFile() {
        return game.resolve("octo").resolve("mods.json");
    }

    // ---------------------------------------------------------------- reading

    /** Every mod in the folder, on and off, by name. */
    public List<InstalledMod> list() {
        List<InstalledMod> out = new ArrayList<>();
        Map<String, Source> sources = sources();

        if (!Files.isDirectory(folder())) {
            return out;
        }

        try (var files = Files.list(folder())) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                boolean enabled = name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".litemod");
                boolean disabled = name.endsWith(DISABLED);

                if (!Files.isRegularFile(file) || !enabled && !disabled) {
                    continue;
                }

                Source source = sources.get(enabled ? name : name.substring(0, name.length() - DISABLED.length()));
                InstalledMod mod = read(file, enabled, source == null ? null : source.projectId());

                // A jar that names nothing itself (a library's container):
                // what Modrinth calls it.
                if (mod.id() == null && source != null && source.title() != null) {
                    String title = source.title().replaceAll("\\s+v?\\d[\\w.+-]*.*$", "").strip();
                    mod = new InstalledMod(mod.file(), null, title.isEmpty() ? source.title() : title, mod.version(),
                            mod.loader(), mod.description(), mod.icon(), mod.enabled(), mod.projectId());
                }

                out.add(mod);
            }
        } catch (IOException e) {
            return out;
        }

        out.sort(Comparator.comparing(mod -> mod.name().toLowerCase(java.util.Locale.ROOT)));
        return out;
    }

    private static final Pattern TOML_STRING = Pattern.compile("(?m)^\\s*(modId|displayName|version|description|logoFile)"
            + "\\s*=\\s*(?:\"\"\"([\\s\\S]*?)\"\"\"|'''([\\s\\S]*?)'''|\"((?:[^\"\\\\]|\\\\.)*)\"|'([^']*)')");

    /** A jar's name, version, loader and icon, from whichever metadata it has. */
    static InstalledMod read(Path file, boolean enabled, String projectId) {
        String fallback = file.getFileName().toString().replaceAll("(\\.jar|\\.zip|\\.litemod)?(\\.disabled)?$", "");

        try (ZipFile zip = new ZipFile(file.toFile())) {
            JsonObject fabric = json(zip, "fabric.mod.json");

            if (fabric != null) {
                return new InstalledMod(file, text(fabric, "id"), or(text(fabric, "name"), fallback),
                        text(fabric, "version"), "Fabric", text(fabric, "description"),
                        bytes(zip, icon(fabric.get("icon"))), enabled, projectId);
            }

            JsonObject quilt = json(zip, "quilt.mod.json");

            if (quilt != null && quilt.get("quilt_loader") instanceof JsonObject loader) {
                JsonObject metadata = loader.get("metadata") instanceof JsonObject m ? m : new JsonObject();
                return new InstalledMod(file, text(loader, "id"), or(text(metadata, "name"), fallback),
                        text(loader, "version"), "Quilt", text(metadata, "description"),
                        bytes(zip, icon(metadata.get("icon"))), enabled, projectId);
            }

            for (String toml : new String[] { "META-INF/neoforge.mods.toml", "META-INF/mods.toml" }) {
                ZipEntry entry = zip.getEntry(toml);

                if (entry != null) {
                    Map<String, String> values = new LinkedHashMap<>();
                    String text = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                    int mods = Math.max(0, text.indexOf("[[mods]]"));
                    Matcher matcher = TOML_STRING.matcher(text);

                    // The logo may sit above [[mods]]; the rest is the first mod's.
                    while (matcher.find()) {
                        String key = matcher.group(1);
                        String value = matcher.group(2) != null ? matcher.group(2)
                                : matcher.group(3) != null ? matcher.group(3)
                                        : matcher.group(4) != null ? matcher.group(4) : matcher.group(5);

                        if ((key.equals("logoFile") || matcher.start() > mods) && !values.containsKey(key)) {
                            values.put(key, value.strip());
                        }
                    }

                    String version = values.get("version");

                    if (version == null || version.contains("${")) {
                        version = manifestVersion(zip);
                    }

                    return new InstalledMod(file, values.get("modId"), or(values.get("displayName"), fallback),
                            version, toml.contains("neoforge") ? "NeoForge" : "Forge", values.get("description"),
                            bytes(zip, values.get("logoFile")), enabled, projectId);
                }
            }

            return new InstalledMod(file, null, fallback, manifestVersion(zip), zip.getEntry("mcmod.info") != null
                    ? "Forge (legacy)" : "", null, null, enabled, projectId);
        } catch (IOException | RuntimeException e) {
            return new InstalledMod(file, null, fallback, null, "", null, null, enabled, projectId);
        }
    }

    private static String icon(JsonElement icon) {
        if (icon == null || icon.isJsonNull()) {
            return null;
        }

        if (icon.isJsonPrimitive()) {
            return icon.getAsString();
        }

        // {"16": "a.png", "128": "b.png"}: the largest.
        String best = null;
        int size = -1;

        for (var entry : icon.getAsJsonObject().entrySet()) {
            int at = parse(entry.getKey());

            if (at > size) {
                size = at;
                best = entry.getValue().getAsString();
            }
        }

        return best;
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static JsonObject json(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);

        if (entry == null) {
            return null;
        }

        try (InputStream in = zip.getInputStream(entry)) {
            JsonElement parsed = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] bytes(ZipFile zip, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        try {
            ZipEntry entry = zip.getEntry(path.startsWith("/") ? path.substring(1) : path);

            if (entry == null || entry.getSize() > 2_000_000) {
                return null;
            }

            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static String manifestVersion(ZipFile zip) {
        try {
            ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");

            if (entry == null) {
                return null;
            }

            try (InputStream in = zip.getInputStream(entry)) {
                return new Manifest(in).getMainAttributes().getValue("Implementation-Version");
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // --------------------------------------------------------------- changing

    /** Turns a mod on or off. */
    public InstalledMod setEnabled(InstalledMod mod, boolean enabled) throws IOException {
        if (mod.enabled() == enabled) {
            return mod;
        }

        String name = mod.fileName();
        Path target = mod.file().resolveSibling(enabled ? name.substring(0, name.length() - DISABLED.length())
                : name + DISABLED);
        Files.move(mod.file(), target, StandardCopyOption.ATOMIC_MOVE);
        return read(target, enabled, mod.projectId());
    }

    /** Removes a mod's file. */
    public void delete(InstalledMod mod) throws IOException {
        Files.deleteIfExists(mod.file());
        Map<String, Source> sources = sources();
        String name = mod.fileName().endsWith(DISABLED)
                ? mod.fileName().substring(0, mod.fileName().length() - DISABLED.length()) : mod.fileName();

        if (sources.remove(name) != null) {
            saveSources(sources);
        }
    }

    /**
     * Installs a mod from Modrinth with everything it requires, each in the
     * version that best fits the game version. A dependency already installed
     * is left as it is.
     *
     * @param progress told each file as it is downloaded
     */
    public Installed install(String projectId, String gameVersion, java.util.function.Consumer<String> progress)
            throws IOException, InterruptedException {
        List<String> added = new ArrayList<>();
        List<String> already = new ArrayList<>();
        Set<String> present = new HashSet<>();
        Map<String, Source> sources = sources();
        sources.values().forEach(source -> present.add(source.projectId()));
        list().forEach(mod -> {
            if (mod.projectId() != null) {
                present.add(mod.projectId());
            }
        });

        installOne(projectId, null, gameVersion, present, sources, added, already, progress, 0, true);
        saveSources(sources);
        return new Installed(added, already);
    }

    private void installOne(String projectId, String versionId, String gameVersion, Set<String> present,
            Map<String, Source> sources, List<String> added, List<String> already,
            java.util.function.Consumer<String> progress, int depth, boolean asked)
            throws IOException, InterruptedException {
        if (depth > 8 || projectId == null && versionId == null) {
            return;
        }

        if (projectId != null && present.contains(projectId)) {
            if (asked) {
                already.add(projectId);
            }

            return;
        }

        Modrinth.Version version = versionId != null ? Modrinth.version(versionId)
                : choose(Modrinth.versions(projectId), gameVersion);

        if (version == null) {
            throw new IOException("vanilla".equals(onlyLoader)
                    ? "plain Minecraft takes no mods: choose a loader beside Play first"
                    : onlyLoader != null ? "this mod has no " + onlyLoader + " build for Minecraft " + gameVersion
                            : "no version of " + projectId + " can be installed");
        }

        present.add(version.projectId());
        progress.accept(version.fileName());
        Path file = Modrinth.download(version, folder());
        sources.put(file.getFileName().toString(), new Source(version.projectId(), version.id(), version.name()));
        added.add(version.fileName());

        for (Modrinth.Dependency dependency : version.dependencies()) {
            if ("required".equals(dependency.type())) {
                installOne(dependency.projectId(), dependency.versionId(), gameVersion, present, sources, added,
                        already, progress, depth + 1, false);
            }
        }
    }

    /** Replaces an installed mod with another version of it. */
    public InstalledMod update(InstalledMod mod, Modrinth.Version version) throws IOException, InterruptedException {
        Path file = Modrinth.download(version, folder());
        Map<String, Source> sources = sources();

        if (!file.equals(mod.file())) {
            Files.deleteIfExists(mod.file());
            sources.remove(mod.fileName());
        }

        sources.put(file.getFileName().toString(), new Source(version.projectId(), version.id(), version.name()));
        saveSources(sources);
        return read(file, true, version.projectId());
    }

    /**
     * The newer version, for each installed mod Modrinth knows: found by each
     * file's hash, so mods installed by hand are included.
     */
    public Map<Path, Modrinth.Version> updates(String gameVersion) throws IOException, InterruptedException {
        Map<String, InstalledMod> byHash = new LinkedHashMap<>();

        for (InstalledMod mod : list()) {
            if (mod.enabled()) {
                byHash.put(Modrinth.hash(mod.file(), "SHA-1"), mod);
            }
        }

        Map<String, Modrinth.Version> current = Modrinth.identify(new ArrayList<>(byHash.keySet()));
        Map<Path, Modrinth.Version> out = new LinkedHashMap<>();

        for (Map.Entry<String, Modrinth.Version> entry : current.entrySet()) {
            Modrinth.Version installed = entry.getValue();
            Modrinth.Version best = choose(Modrinth.versions(installed.projectId()), gameVersion);

            if (best != null && !best.id().equals(installed.id())
                    && best.published().compareTo(installed.published()) > 0) {
                out.put(byHash.get(entry.getKey()).file(), best);
            }
        }

        return out;
    }

    Map<String, Source> sources() {
        try {
            if (Files.isRegularFile(sourcesFile())) {
                Map<String, Source> read = GSON.fromJson(Files.readString(sourcesFile(), StandardCharsets.UTF_8),
                        new TypeToken<LinkedHashMap<String, Source>>() { }.getType());
                return read == null ? new LinkedHashMap<>() : new LinkedHashMap<>(read);
            }
        } catch (IOException | RuntimeException e) {
            // None recorded, then.
        }

        return new LinkedHashMap<>();
    }

    private void saveSources(Map<String, Source> sources) throws IOException {
        Files.createDirectories(sourcesFile().getParent());
        Files.writeString(sourcesFile(), GSON.toJson(sources) + "\n", StandardCharsets.UTF_8);
    }
}
