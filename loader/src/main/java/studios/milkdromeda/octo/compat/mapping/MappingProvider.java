package studios.milkdromeda.octo.compat.mapping;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import studios.milkdromeda.octo.compat.Era.MappingNamespace;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Fetches the published name tables a translation needs, and joins them.
 *
 * <p>Since 26.1 the game ships under Mojang's own names, so a mod built for any
 * earlier version speaks a different language from the game it is running in:
 * Fabric's intermediary ({@code class_310}, {@code method_1551}), Forge's SRG
 * ({@code m_91087_}), or Mojang's names as they stood in that release. Every one
 * of those was published, version by version, as a table keyed by Mojang's
 * obfuscated names — Mojang's deobfuscation maps, Fabric's intermediary files,
 * Forge's MCPConfig — and joining them on the obfuscated name gives a table from
 * any of them to any other.
 *
 * <p>The pivot is intermediary. Its numbers were assigned once and never reused,
 * so {@code class_2960} is the same class in 1.14 and in 1.21.11, whatever Mojang
 * was calling it that year. A mod's names are taken to intermediary through the
 * tables for the version it was built for, and intermediary is taken to Mojang's
 * names through the tables for the newest version that still had it — the names
 * 26.x carries. That is how a 1.21.1 mod's {@code ResourceLocation} arrives as
 * today's {@code Identifier}.
 *
 * <p>Everything downloaded and everything composed is kept under
 * {@code .octo/cache/mappings}, so the network is needed once per version and
 * never again. {@code -Docto.offline=true} uses only what is already there.
 */
public final class MappingProvider {
    private static final OctoLog LOG = OctoLog.of(MappingProvider.class);

    /**
     * The newest release with both intermediary and Mojang's maps: the last
     * obfuscated one, whose Mojang names 26.x inherited.
     */
    public static final String LATEST_INTERMEDIARY = System.getProperty("octo.mappingsTarget", "1.21.11");

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String INTERMEDIARY = "https://maven.fabricmc.net/net/fabricmc/intermediary/%1$s/"
            + "intermediary-%1$s-v2.jar";
    private static final String MCP_CONFIG = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/";

    private final Path cache;
    private final boolean offline;
    private final Map<String, Optional<MappingSet>> composed = new ConcurrentHashMap<>();
    private final Map<String, Optional<NameTable>> tables = new ConcurrentHashMap<>();
    private volatile HttpClient http;

    public MappingProvider(Path cache) {
        this(cache, Boolean.getBoolean("octo.offline"));
    }

    public MappingProvider(Path cache, boolean offline) {
        this.cache = cache;
        this.offline = offline;
    }

    /**
     * A table from the names a mod built for {@code version} uses to the names
     * the game uses now.
     *
     * @param namespace what the mod's bytecode speaks
     * @param version   the Minecraft release it was built for, or {@code null}
     *                  when that is not known
     * @return the table, or empty when the data could not be had
     */
    public Optional<MappingSet> toCurrent(MappingNamespace namespace, String version) {
        String release = version == null || version.isBlank() ? LATEST_INTERMEDIARY : version;
        String key = namespace + "-" + release + "-to-mojang-" + LATEST_INTERMEDIARY;
        return composed.computeIfAbsent(key, ignored -> compose(namespace, release, key));
    }

    private Optional<MappingSet> compose(MappingNamespace namespace, String release, String key) {
        Path file = cache.resolve(key + ".octomap");

        if (Files.isRegularFile(file)) {
            try {
                MappingSet loaded = read(file, key);
                LOG.debug("loaded cached mappings {}: {}", key, loaded);
                return Optional.of(loaded);
            } catch (IOException | RuntimeException e) {
                LOG.warn("cached mappings {} are unreadable and will be rebuilt: {}", file, e.toString());
            }
        }

        long started = System.nanoTime();
        Optional<MappingSet> built = switch (namespace) {
            case INTERMEDIARY -> intermediaryToCurrent(release);
            case MOJANG -> mojangToCurrent(release);
            case SRG -> srgToCurrent(release);
            default -> Optional.empty();
        };

        built.ifPresent(set -> {
            LOG.info("built mappings {} in {} ms: {}", key, (System.nanoTime() - started) / 1_000_000, set);

            try {
                write(file, set);
            } catch (IOException e) {
                LOG.warn("could not cache mappings {}: {}", file, e.toString());
            }
        });

        return built;
    }

    /**
     * A table from the obfuscated names of one release's game jar to the names
     * the game uses now — what it takes to bring a class that release had back
     * into this one.
     *
     * <p>Every name lands where a mod translated from that release would have
     * put it: today's name where the class or member survives, and that
     * release's own Mojang name where it does not. A class added back under the
     * second kind of name is exactly the class a rebuilt mod is asking for.
     */
    public Optional<MappingSet> obfuscatedToCurrent(String release) {
        String key = "official-" + release + "-to-mojang-" + LATEST_INTERMEDIARY;
        return composed.computeIfAbsent(key, ignored -> {
            Path file = cache.resolve(key + ".octomap");

            if (Files.isRegularFile(file)) {
                try {
                    return Optional.of(read(file, key));
                } catch (IOException | RuntimeException e) {
                    LOG.warn("cached mappings {} are unreadable and will be rebuilt: {}", file, e.toString());
                }
            }

            Optional<MappingSet> built = officialToCurrent(release);
            built.ifPresent(set -> {
                try {
                    write(file, set);
                } catch (IOException e) {
                    LOG.warn("could not cache mappings {}: {}", file, e.toString());
                }
            });
            return built;
        });
    }

    /**
     * The renames between the newest release with published tables and the
     * game running now, worked out by comparing the two jars.
     *
     * <p>Every other table here translates into 1.21.11's names, and the year
     * releases renamed things after that without publishing anything. This is
     * the last step of every translation: the furnace's
     * {@code cookingProgress} in 1.21.11 is {@code cookingTimer} in 26.3.
     *
     * @param gameJars    the running game's jars
     * @param gameVersion the running game's version
     * @param side        which jar those are; a dedicated server's has no
     *                    client classes, so its comparison covers less
     * @return the renames, or empty when the game is not a year release or
     *         the old jar could not be had
     */
    public Optional<MappingSet> latestToGame(List<Path> gameJars, String gameVersion, String side) {
        return releaseToGame(LATEST_INTERMEDIARY, gameJars, gameVersion, side);
    }

    /**
     * The same comparison from an older release's own names, for what its
     * route through intermediary loses: a member Mojang reworked rather than
     * renamed got a new intermediary number, so nothing links 1.21's
     * {@code cookingProgress} to the {@code cookingTimer} it became in 1.21.2.
     */
    public Optional<MappingSet> releaseToGame(String release, List<Path> gameJars, String gameVersion, String side) {
        if (release == null || gameJars.isEmpty() || gameVersion == null
                || !gameVersion.matches("\\d{2,}\\..*")) {
            return Optional.empty();
        }

        String key = "mojang-" + release + "-to-" + gameVersion + "-" + side + "-v2";
        return composed.computeIfAbsent(key, ignored -> {
            Path file = cache.resolve(key + ".octomap");

            if (Files.isRegularFile(file)) {
                try {
                    return Optional.of(read(file, key));
                } catch (IOException | RuntimeException e) {
                    LOG.warn("cached renames {} are unreadable and will be worked out again: {}", file, e.toString());
                }
            }

            Optional<Path> oldJar = clientJar(release);
            Optional<NameTable> names = oldJar.isPresent() ? mojang(release) : Optional.empty();

            if (oldJar.isEmpty() || names.isEmpty()) {
                LOG.info("Minecraft {} could not be had, so renames since it are left to the bypass", release);
                return Optional.empty();
            }

            try {
                long started = System.nanoTime();
                Optional<MappingSet> translated = release.equals(LATEST_INTERMEDIARY) ? Optional.empty()
                        : officialToCurrent(release);
                Map<String, VersionDiff.Shape> old = translated.isPresent()
                        ? VersionDiff.readThrough(oldJar.get(), translated.get().remapper())
                        : VersionDiff.readObfuscated(oldJar.get(), names.get());
                MappingSet renames = VersionDiff.between(old, VersionDiff.readNamed(gameJars),
                        "mojang " + release + "->" + gameVersion);
                LOG.info("found {} member rename(s) between Minecraft {} and {} in {} ms", renames.size(),
                        release, gameVersion, (System.nanoTime() - started) / 1_000_000);
                write(file, renames);
                return Optional.of(renames);
            } catch (IOException | RuntimeException e) {
                LOG.warn("could not compare Minecraft {} with {}: {}", release, gameVersion, e.toString());
                return Optional.empty();
            }
        });
    }

    /**
     * The client jar of an old release, which carries every class of that
     * release, common and client alike. Downloaded once into
     * {@code .octo/cache/game}.
     */
    public Optional<Path> clientJar(String release) {
        Path directory = cache.resolveSibling("game");
        String fileName = "client-" + release + ".jar";

        if (Files.isRegularFile(directory.resolve(fileName))) {
            return Optional.of(directory.resolve(fileName));
        }

        return versionDownloads(release)
                .filter(downloads -> downloads.has("client"))
                .flatMap(downloads -> fetch(downloads.getAsJsonObject("client").get("url").getAsString(), fileName,
                        directory));
    }

    // ------------------------------------------------------------- composing

    private Optional<MappingSet> officialToCurrent(String release) {
        Optional<NameTable> official = mojang(release);
        Optional<NameTable> pivot = intermediary(release);
        Optional<Map<String, Map<String, String>>> latest = intermediaryToMojang(LATEST_INTERMEDIARY);

        if (official.isEmpty() || pivot.isEmpty() || latest.isEmpty()) {
            return Optional.empty();
        }

        NameTable names = official.get();
        NameTable ids = pivot.get();
        Map<String, String> classIds = latest.get().get("c");
        Map<String, String> methodIds = latest.get().get("m");
        Map<String, String> fieldIds = latest.get().get("f");
        MappingSet.Builder builder = MappingSet.builder("official " + release + "->mojang " + LATEST_INTERMEDIARY);

        names.classes.forEach((obf, named) -> {
            String id = ids.classes.get(obf);
            String now = id == null ? null : classIds.get(id);
            builder.mapClass(obf, now != null ? now : named);
        });

        names.methods.forEach((key, named) -> {
            int dot = key.indexOf('.');
            int open = key.indexOf('(');

            if (dot < 0 || open < dot) {
                return;
            }

            String id = ids.methods.get(key);
            String now = id == null ? null : methodIds.get(id);
            builder.mapMethod(key.substring(0, dot), key.substring(dot + 1, open), key.substring(open),
                    now != null ? now : named);
        });

        names.fields.forEach((key, named) -> {
            int dot = key.indexOf('.');

            if (dot < 0) {
                return;
            }

            String id = ids.fields.get(key);
            String now = id == null ? null : fieldIds.get(id);
            builder.mapField(key.substring(0, dot), key.substring(dot + 1), names.fieldDescriptors.get(key),
                    now != null ? now : named);
        });

        return Optional.of(builder.build());
    }

    /** intermediary to today's names, the newest table first and the mod's own version behind it. */
    private Optional<MappingSet> intermediaryToCurrent(String release) {
        Optional<Map<String, Map<String, String>>> latest = intermediaryToMojang(LATEST_INTERMEDIARY);

        if (latest.isEmpty()) {
            return Optional.empty();
        }

        MappingSet primary = asIntermediarySet(latest.get(), "intermediary->mojang " + LATEST_INTERMEDIARY);

        if (release.equals(LATEST_INTERMEDIARY)) {
            return Optional.of(primary);
        }

        return Optional.of(primary.orElse(intermediaryToMojang(release)
                .map(own -> asIntermediarySet(own, "intermediary->mojang " + release)).orElse(MappingSet.EMPTY)));
    }

    /** Mojang's names of an old release to today's, through intermediary. */
    private Optional<MappingSet> mojangToCurrent(String release) {
        Optional<NameTable> official = mojang(release);
        Optional<NameTable> pivot = intermediary(release);
        Optional<Map<String, Map<String, String>>> latest = intermediaryToMojang(LATEST_INTERMEDIARY);

        if (official.isEmpty() || pivot.isEmpty() || latest.isEmpty()) {
            return Optional.empty();
        }

        NameTable old = official.get();
        NameTable ids = pivot.get();
        Map<String, String> classIds = latest.get().get("c");
        Map<String, String> methodIds = latest.get().get("m");
        Map<String, String> fieldIds = latest.get().get("f");
        MappingSet.Builder builder = MappingSet.builder("mojang " + release + "->mojang " + LATEST_INTERMEDIARY);
        Map<String, Set<String>> methodRenames = new HashMap<>();
        Map<String, Set<String>> fieldRenames = new HashMap<>();

        old.classes.forEach((obf, named) -> {
            String id = ids.classes.get(obf);
            String now = id == null ? null : classIds.get(id);

            if (now != null) {
                builder.mapClass(named, now);
            }
        });

        old.methods.forEach((key, named) -> {
            String id = ids.methods.get(key);
            String now = id == null ? null : methodIds.get(id);
            int dot = key.indexOf('.');
            int open = key.indexOf('(');

            if (now == null || dot < 0 || open < 0) {
                return;
            }

            String owner = old.classes.getOrDefault(key.substring(0, dot), key.substring(0, dot));
            String descriptor = old.mapDescriptor(key.substring(open));
            methodRenames.computeIfAbsent(named, ignored -> new HashSet<>()).add(now);

            if (!now.equals(named)) {
                builder.mapMethod(owner, named, descriptor, now);
            }
        });

        old.fields.forEach((key, named) -> {
            String id = ids.fields.get(key);
            String now = id == null ? null : fieldIds.get(id);
            int dot = key.indexOf('.');

            if (now == null || dot < 0) {
                return;
            }

            String owner = old.classes.getOrDefault(key.substring(0, dot), key.substring(0, dot));
            String obfDescriptor = old.fieldDescriptors.get(key);
            fieldRenames.computeIfAbsent(named, ignored -> new HashSet<>()).add(now);

            if (!now.equals(named)) {
                builder.mapField(owner, named, obfDescriptor == null ? null : old.mapDescriptor(obfDescriptor), now);
            }
        });

        // A rename that is the same everywhere a name appears is safe to apply
        // without an owner, which is what reaches a member called through a
        // mod's own subclass.
        methodRenames.forEach((named, targets) -> {
            if (targets.size() == 1 && !targets.contains(named)) {
                builder.mapMethodGlobally(named, targets.iterator().next());
            }
        });

        fieldRenames.forEach((named, targets) -> {
            if (targets.size() == 1 && !targets.contains(named)) {
                builder.mapFieldGlobally(named, targets.iterator().next());
            }
        });

        return Optional.of(builder.build());
    }

    /**
     * Forge's runtime names from 1.17 to 1.20.4 — Mojang's class names of that
     * release, SRG member names — to today's.
     */
    private Optional<MappingSet> srgToCurrent(String release) {
        Optional<NameTable> srg = mcpConfig(release);
        Optional<NameTable> official = mojang(release);
        Optional<NameTable> pivot = intermediary(release);
        Optional<Map<String, Map<String, String>>> latest = intermediaryToMojang(LATEST_INTERMEDIARY);

        if (srg.isEmpty() || official.isEmpty() || pivot.isEmpty() || latest.isEmpty()) {
            return Optional.empty();
        }

        NameTable ids = pivot.get();
        Map<String, String> classIds = latest.get().get("c");
        Map<String, String> methodIds = latest.get().get("m");
        Map<String, String> fieldIds = latest.get().get("f");
        MappingSet.Builder builder = MappingSet.builder("srg " + release + "->mojang " + LATEST_INTERMEDIARY);

        official.get().classes.forEach((obf, named) -> {
            String id = ids.classes.get(obf);
            String now = id == null ? null : classIds.get(id);

            if (now != null) {
                builder.mapClass(named, now);
            }
        });

        srg.get().methods.forEach((key, srgName) -> {
            String id = ids.methods.get(key);
            String now = id == null ? null : methodIds.get(id);

            if (now != null && MappingSet.isUniqueMemberName(srgName)) {
                builder.mapMethodGlobally(srgName, now);
            }
        });

        srg.get().fields.forEach((key, srgName) -> {
            String id = ids.fields.get(key);
            String now = id == null ? null : fieldIds.get(id);

            if (now != null && MappingSet.isUniqueMemberName(srgName)) {
                builder.mapFieldGlobally(srgName, now);
            }
        });

        return Optional.of(builder.build());
    }

    /**
     * intermediary to one release's Mojang names, as three maps: classes
     * ({@code c}), methods ({@code m}) and fields ({@code f}). Member names in
     * intermediary are unique on their own, so they need no owner.
     */
    private Optional<Map<String, Map<String, String>>> intermediaryToMojang(String release) {
        Optional<NameTable> ids = intermediary(release);
        Optional<NameTable> names = mojang(release);

        if (ids.isEmpty() || names.isEmpty()) {
            return Optional.empty();
        }

        Map<String, String> classes = new HashMap<>();
        Map<String, String> methods = new HashMap<>();
        Map<String, String> fields = new HashMap<>();
        join(ids.get().classes, names.get().classes, classes);
        join(ids.get().methods, names.get().methods, methods);
        join(ids.get().fields, names.get().fields, fields);
        return Optional.of(Map.of("c", classes, "m", methods, "f", fields));
    }

    private static void join(Map<String, String> left, Map<String, String> right, Map<String, String> into) {
        left.forEach((obf, id) -> {
            String named = right.get(obf);

            if (named != null) {
                into.putIfAbsent(id, named);
            }
        });
    }

    private static MappingSet asIntermediarySet(Map<String, Map<String, String>> maps, String name) {
        MappingSet.Builder builder = MappingSet.builder(name);
        maps.get("c").forEach(builder::mapClass);
        maps.get("m").forEach(builder::mapMethodGlobally);
        maps.get("f").forEach(builder::mapFieldGlobally);
        return builder.build();
    }

    // -------------------------------------------------------------- sources

    private Optional<NameTable> intermediary(String release) {
        return tables.computeIfAbsent("intermediary-" + release, key -> {
            Optional<Path> jar = fetch(INTERMEDIARY.formatted(release), "intermediary-" + release + "-v2.jar");

            if (jar.isEmpty()) {
                return Optional.empty();
            }

            try (ZipFile zip = new ZipFile(jar.get().toFile())) {
                ZipEntry entry = zip.getEntry("mappings/mappings.tiny");

                if (entry == null) {
                    return Optional.empty();
                }

                try (BufferedReader reader = reader(zip.getInputStream(entry))) {
                    return Optional.of(NameTable.readTiny(reader, "intermediary"));
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("could not read intermediary for {}: {}", release, e.toString());
                return Optional.empty();
            }
        });
    }

    private Optional<NameTable> mojang(String release) {
        return tables.computeIfAbsent("mojang-" + release, key -> {
            Optional<Path> mappings = mojangMappings(release);

            if (mappings.isEmpty()) {
                return Optional.empty();
            }

            try (BufferedReader reader = Files.newBufferedReader(mappings.get(), StandardCharsets.UTF_8)) {
                return Optional.of(NameTable.readProguard(reader));
            } catch (IOException | RuntimeException e) {
                LOG.warn("could not read Mojang's mappings for {}: {}", release, e.toString());
                return Optional.empty();
            }
        });
    }

    private Optional<Path> mojangMappings(String release) {
        String fileName = "client-" + release + ".txt";
        Path cached = cache.resolve("raw").resolve(fileName);

        if (Files.isRegularFile(cached)) {
            return Optional.of(cached);
        }

        Optional<JsonObject> downloads = versionDownloads(release);

        if (downloads.isEmpty()) {
            return Optional.empty();
        }

        if (!downloads.get().has("client_mappings")) {
            LOG.info("Minecraft {} has no published mappings", release);
            return Optional.empty();
        }

        return fetch(downloads.get().getAsJsonObject("client_mappings").get("url").getAsString(), fileName);
    }

    /** The {@code downloads} block of a release's version file, from Mojang's version list. */
    private Optional<JsonObject> versionDownloads(String release) {
        Optional<Path> manifest = fetch(MANIFEST, "version_manifest_v2.json");

        if (manifest.isEmpty()) {
            return Optional.empty();
        }

        try {
            JsonObject root = JsonParser.parseString(Files.readString(manifest.get())).getAsJsonObject();

            for (JsonElement entry : root.getAsJsonArray("versions")) {
                JsonObject version = entry.getAsJsonObject();

                if (!version.get("id").getAsString().equals(release)) {
                    continue;
                }

                Optional<Path> json = fetch(version.get("url").getAsString(), "version-" + release + ".json");

                if (json.isEmpty()) {
                    return Optional.empty();
                }

                return Optional.ofNullable(JsonParser.parseString(Files.readString(json.get())).getAsJsonObject()
                        .getAsJsonObject("downloads"));
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not read Mojang's version file for {}: {}", release, e.toString());
        }

        return Optional.empty();
    }

    /** Forge's MCPConfig for a release: obfuscated to SRG. */
    private Optional<NameTable> mcpConfig(String release) {
        return tables.computeIfAbsent("mcp-" + release, key -> {
            Optional<Path> metadata = fetch(MCP_CONFIG + "maven-metadata.xml", "mcp_config-metadata.xml");

            if (metadata.isEmpty()) {
                return Optional.empty();
            }

            try {
                String latest = null;

                for (String line : Files.readAllLines(metadata.get())) {
                    String trimmed = line.trim();

                    if (trimmed.startsWith("<version>" + release + "-")) {
                        latest = trimmed.substring(9, trimmed.indexOf("</version>"));
                    }
                }

                if (latest == null) {
                    return Optional.empty();
                }

                Optional<Path> zip = fetch(MCP_CONFIG + latest + "/mcp_config-" + latest + ".zip",
                        "mcp_config-" + latest + ".zip");

                if (zip.isEmpty()) {
                    return Optional.empty();
                }

                try (ZipFile file = new ZipFile(zip.get().toFile())) {
                    ZipEntry entry = file.getEntry("config/joined.tsrg");

                    if (entry == null) {
                        return Optional.empty();
                    }

                    try (BufferedReader reader = reader(file.getInputStream(entry))) {
                        return Optional.of(NameTable.readTsrg2(reader));
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("could not read MCPConfig for {}: {}", release, e.toString());
                return Optional.empty();
            }
        });
    }

    private static BufferedReader reader(InputStream in) {
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** Downloads a file into the cache once; afterwards the cached copy is used. */
    private Optional<Path> fetch(String url, String fileName) {
        return fetch(url, fileName, cache.resolve("raw"));
    }

    private Optional<Path> fetch(String url, String fileName, Path directory) {
        Path target = directory.resolve(fileName);

        if (Files.isRegularFile(target)) {
            return Optional.of(target);
        }

        if (offline) {
            LOG.debug("offline: {} is not cached", fileName);
            return Optional.empty();
        }

        try {
            Files.createDirectories(target.getParent());
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                    .header("User-Agent", "Octo-Loader").GET().build();
            Path temporary = target.resolveSibling(fileName + ".part");
            HttpResponse<Path> response = client().send(request, HttpResponse.BodyHandlers.ofFile(temporary));

            if (response.statusCode() != 200) {
                Files.deleteIfExists(temporary);
                LOG.debug("{} answered {}", url, response.statusCode());
                return Optional.empty();
            }

            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("downloaded {}", fileName);
            return Optional.of(target);
        } catch (IOException e) {
            LOG.warn("could not download {}: {}", url, e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private HttpClient client() {
        HttpClient current = http;

        if (current == null) {
            synchronized (this) {
                if (http == null) {
                    http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                            .connectTimeout(Duration.ofSeconds(20)).build();
                }

                current = http;
            }
        }

        return current;
    }

    // ------------------------------------------------------- the cache file

    /** A plain, line-per-entry form of a set, fast to read back. */
    static void write(Path file, MappingSet set) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".part");

        try (BufferedWriter out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            out.write("octomap\t1\t" + set.name().replace('\t', ' ') + "\n");
            set.export(new MappingSet.Visitor() {
                @Override
                public void visitClass(String from, String to) throws IOException {
                    out.write("c\t" + from + "\t" + to + "\n");
                }

                @Override
                public void visitMethod(String owner, String name, String descriptor, String to) throws IOException {
                    out.write("m\t" + owner + "\t" + name + "\t" + (descriptor == null ? "" : descriptor) + "\t"
                            + to + "\n");
                }

                @Override
                public void visitField(String owner, String name, String descriptor, String to) throws IOException {
                    out.write("f\t" + owner + "\t" + name + "\t" + (descriptor == null ? "" : descriptor) + "\t"
                            + to + "\n");
                }

                @Override
                public void visitGlobalMethod(String name, String to) throws IOException {
                    out.write("M\t" + name + "\t" + to + "\n");
                }

                @Override
                public void visitGlobalField(String name, String to) throws IOException {
                    out.write("F\t" + name + "\t" + to + "\n");
                }
            });
        }

        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    static MappingSet read(Path file, String fallbackName) throws IOException {
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = in.readLine();

            if (header == null || !header.startsWith("octomap\t1")) {
                throw new IOException("not an Octo mapping cache");
            }

            String[] head = header.split("\t", 3);
            MappingSet.Builder builder = MappingSet.builder(head.length > 2 ? head[2] : fallbackName);
            String line;

            while ((line = in.readLine()) != null) {
                String[] parts = line.split("\t", -1);

                switch (parts[0]) {
                    case "c" -> builder.mapClass(parts[1], parts[2]);
                    case "m" -> builder.mapMethod(parts[1], parts[2], parts[3].isEmpty() ? null : parts[3], parts[4]);
                    case "f" -> builder.mapField(parts[1], parts[2], parts[3].isEmpty() ? null : parts[3], parts[4]);
                    case "M" -> builder.mapMethodGlobally(parts[1], parts[2]);
                    case "F" -> builder.mapFieldGlobally(parts[1], parts[2]);
                    default -> {
                        // A newer cache format; what is understood is still used.
                    }
                }
            }

            return builder.build();
        }
    }
}
