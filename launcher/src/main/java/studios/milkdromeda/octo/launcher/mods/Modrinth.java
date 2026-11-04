package studios.milkdromeda.octo.launcher.mods;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Modrinth, where the mod manager finds mods: its public API, read without an
 * account. Octo loads Fabric, Quilt, Forge and NeoForge mods, and mods built
 * for other Minecraft versions, so every one of those is offered; a version
 * made for the game being played is always preferred when there is one.
 */
public final class Modrinth {
    static final String API = "https://api.modrinth.com/v2";
    /** Every loader Octo reads mods of, in the order a tie is broken. */
    public static final List<String> LOADERS = List.of("neoforge", "fabric", "forge", "quilt");

    private static String userAgent = "MilkdromedaStudios/Octo-Loader (Octo Launcher)";

    /** One mod in a search. */
    public record Project(String id, String slug, String title, String description, String author, long downloads,
            String iconUrl, int color, List<String> loaders, List<String> categories, List<String> gameVersions) {
        public String pageUrl() {
            return "https://modrinth.com/mod/" + slug;
        }
    }

    /** One downloadable version of a mod. */
    public record Version(String id, String projectId, String name, String number, List<String> gameVersions,
            List<String> loaders, String published, String fileUrl, String fileName, String sha512, long size,
            List<Dependency> dependencies) {
    }

    /** What a version needs or works with: {@code required}, {@code optional}, {@code incompatible}, {@code embedded}. */
    public record Dependency(String projectId, String versionId, String type) {
    }

    /** Everything about one mod, for its page in the launcher. */
    public record Details(Project project, String body, List<String> gallery, long followers, String updated,
            String sourceUrl, String issuesUrl, String discordUrl) {
    }

    /** A page of search results. */
    public record Page(List<Project> projects, int total) {
    }

    private Modrinth() {
    }

    public static void userAgent(String value) {
        userAgent = value;
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private static JsonElement get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(20)).header("User-Agent", userAgent).build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Modrinth answered " + response.statusCode() + " for " + path);
        }

        return JsonParser.parseString(response.body());
    }

    private static JsonElement post(String path, JsonObject body) throws IOException, InterruptedException {
        HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(20)).header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Modrinth answered " + response.statusCode() + " for " + path);
        }

        return JsonParser.parseString(response.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ searching

    /**
     * @param gameVersion only mods with a version for it, or {@code null} for
     *                    any: Octo rebuilds mods made for another version
     * @param category    a Modrinth category such as {@code technology}, or {@code null}
     * @param sort        {@code relevance}, {@code downloads}, {@code follows}, {@code newest} or {@code updated}
     */
    public static Page search(String query, String gameVersion, String category, String sort, int offset, int limit)
            throws IOException, InterruptedException {
        return search(query, gameVersion, category, null, sort, offset, limit);
    }

    /**
     * What a search asks Modrinth for: mods, of one loader or of any Octo
     * runs (one group, so any of them matches), for a version and a category
     * when they are given.
     */
    public static JsonArray facets(String gameVersion, String category, String loader) {
        JsonArray facets = new JsonArray();
        facets.add(facet("project_type:mod"));
        JsonArray loaders = new JsonArray();

        if (loader != null && LOADERS.contains(loader)) {
            loaders.add("categories:" + loader);
        } else {
            LOADERS.forEach(each -> loaders.add("categories:" + each));
        }

        facets.add(loaders);

        if (gameVersion != null && !gameVersion.isBlank()) {
            facets.add(facet("versions:" + gameVersion));
        }

        if (category != null && !category.isBlank()) {
            facets.add(facet("categories:" + category));
        }

        return facets;
    }

    /**
     * As {@link #search(String, String, String, String, int, int)}, for mods
     * of one loader: {@code fabric}, {@code quilt}, {@code forge} or
     * {@code neoforge}; {@code null} for mods of any of them, which Octo runs
     * side by side.
     */
    public static Page search(String query, String gameVersion, String category, String loader, String sort,
            int offset, int limit) throws IOException, InterruptedException {
        JsonArray facets = facets(gameVersion, category, loader);
        String path = "/search?query=" + encode(query == null ? "" : query) + "&facets=" + encode(facets.toString())
                + "&index=" + encode(sort == null ? "relevance" : sort) + "&offset=" + offset + "&limit=" + limit;
        return parsePage(get(path).getAsJsonObject());
    }

    private static JsonArray facet(String value) {
        JsonArray array = new JsonArray();
        array.add(value);
        return array;
    }

    static Page parsePage(JsonObject root) {
        List<Project> projects = new ArrayList<>();

        for (JsonElement element : root.getAsJsonArray("hits")) {
            JsonObject hit = element.getAsJsonObject();
            List<String> categories = strings(hit.get("categories"));
            List<String> loaders = new ArrayList<>(categories);
            loaders.retainAll(LOADERS);
            List<String> topics = new ArrayList<>(categories);
            topics.removeAll(LOADERS);
            projects.add(new Project(string(hit, "project_id"), string(hit, "slug"), string(hit, "title"),
                    string(hit, "description"), string(hit, "author"), number(hit, "downloads"),
                    string(hit, "icon_url"), (int) number(hit, "color"), loaders, topics,
                    strings(hit.get("versions"))));
        }

        return new Page(projects, (int) number(root, "total_hits"));
    }

    public static Details details(String projectId) throws IOException, InterruptedException {
        JsonObject project = get("/project/" + encode(projectId)).getAsJsonObject();
        List<String> gallery = new ArrayList<>();

        if (project.get("gallery") instanceof JsonArray images) {
            // The featured image first, then the rest in their order.
            List<JsonObject> sorted = new ArrayList<>();
            images.forEach(image -> sorted.add(image.getAsJsonObject()));
            sorted.sort(Comparator.comparing((JsonObject image) -> !(image.has("featured")
                    && image.get("featured").getAsBoolean())));
            sorted.forEach(image -> gallery.add(string(image, "url")));
        }

        Project summary = new Project(string(project, "id"), string(project, "slug"), string(project, "title"),
                string(project, "description"), null, number(project, "downloads"), string(project, "icon_url"),
                (int) number(project, "color"), strings(project.get("loaders")), strings(project.get("categories")),
                strings(project.get("game_versions")));
        return new Details(summary, string(project, "body"), gallery, number(project, "followers"),
                string(project, "updated"), string(project, "source_url"), string(project, "issues_url"),
                string(project, "discord_url"));
    }

    /** Markdown (and the HTML some descriptions use) as plain paragraphs, for reading in the app. */
    public static String plain(String markdown) {
        if (markdown == null) {
            return "";
        }

        String text = markdown.replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?s)<[^>]+>", "")
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "\u2022 ")
                .replaceAll("[*_`~]{1,3}", "")
                .replaceAll("&nbsp;", " ").replaceAll("&amp;", "&").replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                .replaceAll("(?m)^\\s*([-*_]\\s*){3,}$", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("(?m)^ +| +$", "")
                .replaceAll("\\n{3,}", "\n\n");
        // Emoji, which the app's font has no shapes for: a box each otherwise.
        return entities(text).replaceAll("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]", "")
                .replaceAll("(?m)^ +", "").strip();
    }

    /** {@code &#183;} and {@code &#xB7;} as the characters they name. */
    static String entities(String text) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("&#(x?)([0-9a-fA-F]{1,6});").matcher(text);
        StringBuilder out = new StringBuilder();

        while (matcher.find()) {
            String replacement;

            try {
                int code = Integer.parseInt(matcher.group(2), matcher.group(1).isEmpty() ? 10 : 16);
                replacement = new String(Character.toChars(code));
            } catch (IllegalArgumentException e) {
                replacement = matcher.group();
            }

            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(out);
        return out.toString();
    }

    // ------------------------------------------------------------- versions

    public static List<Version> versions(String projectId) throws IOException, InterruptedException {
        JsonArray loaders = new JsonArray();
        LOADERS.forEach(loaders::add);
        List<Version> out = new ArrayList<>();

        for (JsonElement element : get("/project/" + encode(projectId) + "/version?loaders="
                + encode(loaders.toString())).getAsJsonArray()) {
            Version version = parseVersion(element.getAsJsonObject());

            if (version != null) {
                out.add(version);
            }
        }

        return out;
    }

    public static Version version(String versionId) throws IOException, InterruptedException {
        return parseVersion(get("/version/" + encode(versionId)).getAsJsonObject());
    }

    static Version parseVersion(JsonObject version) {
        JsonObject file = null;

        for (JsonElement element : version.getAsJsonArray("files")) {
            JsonObject candidate = element.getAsJsonObject();

            if (file == null || candidate.has("primary") && candidate.get("primary").getAsBoolean()) {
                file = candidate;
            }
        }

        if (file == null) {
            return null;
        }

        List<Dependency> dependencies = new ArrayList<>();

        if (version.get("dependencies") instanceof JsonArray array) {
            for (JsonElement element : array) {
                JsonObject dependency = element.getAsJsonObject();
                dependencies.add(new Dependency(string(dependency, "project_id"), string(dependency, "version_id"),
                        string(dependency, "dependency_type")));
            }
        }

        JsonObject hashes = file.getAsJsonObject("hashes");
        return new Version(string(version, "id"), string(version, "project_id"), string(version, "name"),
                string(version, "version_number"), strings(version.get("game_versions")),
                strings(version.get("loaders")), string(version, "date_published"), string(file, "url"),
                string(file, "filename"), hashes == null ? null : string(hashes, "sha512"), number(file, "size"),
                dependencies);
    }

    /**
     * The version to install for a game version: one made for it if there is
     * one, newest first; otherwise the newest made for the newest Minecraft
     * the mod supports, which Octo rebuilds for this one.
     */
    public static Version best(List<Version> versions, String gameVersion) {
        Comparator<Version> newest = Comparator.comparing(Version::published).reversed();
        Comparator<Version> byLoader = Comparator.comparingInt(version -> version.loaders().stream()
                .mapToInt(loader -> LOADERS.indexOf(loader) < 0 ? 99 : LOADERS.indexOf(loader)).min().orElse(99));

        List<Version> exact = versions.stream()
                .filter(version -> gameVersion != null && version.gameVersions().contains(gameVersion)).toList();

        if (!exact.isEmpty()) {
            return exact.stream().sorted(newest).findFirst().orElse(null);
        }

        return versions.stream().sorted(Comparator.comparing((Version version) -> newestGameVersion(version))
                .reversed().thenComparing(newest).thenComparing(byLoader)).findFirst().orElse(null);
    }

    /** The newest Minecraft a version is for, as a comparable key: 1.21.1 → 001021001, 26.3 → 026003000. */
    static String newestGameVersion(Version version) {
        String best = "";

        for (String id : version.gameVersions()) {
            String key = versionKey(id);

            if (key.compareTo(best) > 0) {
                best = key;
            }
        }

        return best;
    }

    static String versionKey(String id) {
        if (!id.matches("\\d+(\\.\\d+)*")) {
            return "";
        }

        StringBuilder key = new StringBuilder();
        String[] parts = id.split("\\.");

        for (int i = 0; i < 3; i++) {
            key.append(String.format("%03d", i < parts.length ? Integer.parseInt(parts[i]) : 0));
        }

        return key.toString();
    }

    // ------------------------------------------------------------ installing

    /** Downloads a version's file into a folder, checked against its SHA-512. */
    public static Path download(Version version, Path folder) throws IOException, InterruptedException {
        Files.createDirectories(folder);
        Path target = folder.resolve(version.fileName());
        Path part = folder.resolve(version.fileName() + ".part");
        HttpResponse<InputStream> response = CLIENT.send(HttpRequest.newBuilder(URI.create(version.fileUrl()))
                .timeout(Duration.ofMinutes(5)).header("User-Agent", userAgent).build(),
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException(version.fileName() + " answered " + response.statusCode());
        }

        try (InputStream in = response.body()) {
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
        }

        if (version.sha512() != null && !version.sha512().equalsIgnoreCase(hash(part, "SHA-512"))) {
            Files.deleteIfExists(part);
            throw new IOException(version.fileName() + " did not match its checksum and was discarded");
        }

        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    public static String hash(Path file, String algorithm) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[65536];

            for (int read; (read = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }

            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /**
     * The versions installed files are, found by their SHA-1: which Modrinth
     * project each is, whether or not it was installed through Octo.
     */
    public static Map<String, Version> identify(List<String> sha1s) throws IOException, InterruptedException {
        Map<String, Version> out = new LinkedHashMap<>();

        if (sha1s.isEmpty()) {
            return out;
        }

        JsonObject body = new JsonObject();
        JsonArray hashes = new JsonArray();
        sha1s.forEach(hashes::add);
        body.add("hashes", hashes);
        body.addProperty("algorithm", "sha1");

        for (Map.Entry<String, JsonElement> entry : post("/version_files", body).getAsJsonObject().entrySet()) {
            Version version = parseVersion(entry.getValue().getAsJsonObject());

            if (version != null) {
                out.put(entry.getKey(), version);
            }
        }

        return out;
    }

    /** Several projects at once, by id, for the installed list's names and icons. */
    public static Map<String, Project> projects(List<String> ids) throws IOException, InterruptedException {
        Map<String, Project> out = new LinkedHashMap<>();

        if (ids.isEmpty()) {
            return out;
        }

        JsonArray array = new JsonArray();
        ids.forEach(array::add);

        for (JsonElement element : get("/projects?ids=" + encode(array.toString())).getAsJsonArray()) {
            JsonObject project = element.getAsJsonObject();
            List<String> categories = strings(project.get("categories"));
            List<String> loaders = strings(project.get("loaders"));
            out.put(string(project, "id"), new Project(string(project, "id"), string(project, "slug"),
                    string(project, "title"), string(project, "description"), "",
                    number(project, "downloads"), string(project, "icon_url"), (int) number(project, "color"),
                    loaders, categories, strings(project.get("game_versions"))));
        }

        return out;
    }

    // --------------------------------------------------------------- helpers

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static long number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? 0 : value.getAsLong();
    }

    private static List<String> strings(JsonElement element) {
        List<String> out = new ArrayList<>();

        if (element instanceof JsonArray array) {
            array.forEach(value -> out.add(value.getAsString()));
        }

        return out;
    }
}
