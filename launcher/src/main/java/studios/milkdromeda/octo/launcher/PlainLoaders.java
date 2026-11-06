package studios.milkdromeda.octo.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import studios.milkdromeda.octo.launcher.game.Net;
import studios.milkdromeda.octo.launcher.game.VersionProfile;

/**
 * Minecraft as each mod loader ships it, without Octo: for playing a modpack
 * made for one loader exactly as its authors meant, or plain Minecraft.
 *
 * <p>Each loader is installed the way its own installer would: Fabric's and
 * Quilt's version profiles come from their meta servers, and Forge's and
 * NeoForge's installers run headless, since they patch the game as they
 * install. The newest stable build for the Minecraft version is chosen.
 *
 * <p>A plain game runs in a folder of its own, {@code instances/<loader>-<version>}
 * under the game folder, with its own mods, settings and worlds: the mods Octo
 * translates, and worlds opened with them, never meet a loader that does not
 * expect them. Libraries, assets and versions are shared, so nothing is
 * downloaded twice.
 */
public final class PlainLoaders {
    /** How Play starts the game. */
    public enum Loader {
        OCTO("octo", "With Octo"),
        VANILLA("vanilla", "Vanilla"),
        FABRIC("fabric", "Fabric"),
        QUILT("quilt", "Quilt"),
        FORGE("forge", "Forge"),
        NEOFORGE("neoforge", "NeoForge");

        public final String key;
        public final String label;

        Loader(String key, String label) {
            this.key = key;
            this.label = label;
        }

        /** The loader a setting names; Octo for anything unknown. */
        public static Loader of(String key) {
            for (Loader loader : values()) {
                if (loader.key.equalsIgnoreCase(String.valueOf(key))) {
                    return loader;
                }
            }

            return OCTO;
        }

        public boolean plain() {
            return this != OCTO;
        }

        /** Modrinth's name for the loader, or {@code null} for Octo (every loader) and vanilla (none). */
        public String modrinth() {
            return this == OCTO || this == VANILLA ? null : key;
        }
    }

    private static final String FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader/";
    private static final String QUILT_META = "https://meta.quiltmc.org/v3/versions/loader/";
    private static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge/";
    private static final String FORGE_PROMOTIONS =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    private static final String FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge/";

    private PlainLoaders() {
    }

    /** The folder a plain game plays in: its mods, settings and worlds. */
    public static Path instance(Path game, Loader loader, String minecraft) {
        return game.resolve("instances").resolve(loader.key + "-" + minecraft.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    /**
     * Installs the loader for a Minecraft version, if it is not already.
     *
     * @param java a Java that runs the installers of Forge and NeoForge
     * @return the version id to start
     */
    public static String install(Path game, Loader loader, String minecraft, Path java,
            studios.milkdromeda.octo.installer.ClientInstaller.Log log) throws IOException {
        return switch (loader) {
            case OCTO -> throw new IllegalArgumentException("Octo is installed by Octo's own installer");
            case VANILLA -> minecraft;
            case FABRIC -> profile(game, FABRIC_META, minecraft, "Fabric");
            case QUILT -> profile(game, QUILT_META, minecraft, "Quilt");
            case NEOFORGE -> {
                String version = neoForgeVersion(minecraft);
                yield installer(game, "neoforge-" + version,
                        NEOFORGE_MAVEN + version + "/neoforge-" + version + "-installer.jar", java, log);
            }
            case FORGE -> {
                String version = forgeVersion(minecraft);
                String full = minecraft + "-" + version;
                yield installer(game, minecraft + "-forge-" + version,
                        FORGE_MAVEN + full + "/forge-" + full + "-installer.jar", java, log);
            }
        };
    }

    // ----------------------------------------------------------- Fabric, Quilt

    /** The newest stable loader's version profile, written where launchers keep versions. */
    private static String profile(Path game, String meta, String minecraft, String name) throws IOException {
        JsonElement listed = JsonParser.parseString(Net.text(meta + minecraft));

        if (!(listed instanceof JsonArray loaders) || loaders.isEmpty()) {
            throw new IOException(name + " has no loader for Minecraft " + minecraft + " yet");
        }

        String loaderVersion = newestStable(loaders);
        JsonObject profile = Net.json(meta + minecraft + "/" + loaderVersion + "/profile/json");
        String id = profile.get("id").getAsString();
        Path file = VersionProfile.jsonFile(game, id);

        if (!Files.isRegularFile(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(profile) + "\n",
                    StandardCharsets.UTF_8);
        }

        return id;
    }

    /** The first loader in the list marked stable, or with no pre-release tag; the first at all otherwise. */
    static String newestStable(JsonArray loaders) {
        String first = null;

        for (JsonElement entry : loaders) {
            JsonObject loader = entry.getAsJsonObject().getAsJsonObject("loader");
            String version = loader.get("version").getAsString();
            first = first == null ? version : first;
            boolean stable = loader.has("stable") ? loader.get("stable").getAsBoolean() : !version.contains("-");

            if (stable) {
                return version;
            }
        }

        return first;
    }

    // -------------------------------------------------------- Forge, NeoForge

    /** NeoForge's newest release for the Minecraft version: 21.1.x for 1.21.1, 26.3.0.x for 26.3. */
    static String neoForgeVersion(String minecraft) throws IOException {
        String metadata = Net.text(NEOFORGE_MAVEN + "maven-metadata.xml");
        String chosen = newestNeoForge(metadata, minecraft);

        if (chosen == null) {
            throw new IOException("NeoForge has no release for Minecraft " + minecraft + " yet");
        }

        return chosen;
    }

    static String newestNeoForge(String metadata, String minecraft) {
        String prefix = neoForgePrefix(minecraft);
        List<String> releases = new ArrayList<>();
        List<String> betas = new ArrayList<>();
        Matcher versions = Pattern.compile("<version>([^<]+)</version>").matcher(metadata);

        while (versions.find()) {
            String version = versions.group(1);

            if (version.startsWith(prefix)) {
                (version.contains("-") ? betas : releases).add(version);
            }
        }

        List<String> pool = releases.isEmpty() ? betas : releases;
        return pool.stream().max(PlainLoaders::compareVersions).orElse(null);
    }

    /** 1.21.1 is NeoForge 21.1.x and 1.21 is 21.0.x; since 26.1, Minecraft's own number, then the build. */
    static String neoForgePrefix(String minecraft) {
        String[] parts = minecraft.split("\\.");

        if (parts[0].equals("1") && parts.length >= 2) {
            return parts[1] + "." + (parts.length >= 3 ? parts[2] : "0") + ".";
        }

        return parts.length == 2 ? minecraft + ".0." : minecraft + ".";
    }

    /** Forge's recommended build for the Minecraft version, or its latest if none is recommended. */
    static String forgeVersion(String minecraft) throws IOException {
        JsonObject promos = Net.json(FORGE_PROMOTIONS).getAsJsonObject("promos");

        for (String kind : List.of("-recommended", "-latest")) {
            JsonElement version = promos.get(minecraft + kind);

            if (version != null && version.isJsonPrimitive()) {
                return version.getAsString();
            }
        }

        throw new IOException("Forge has no build for Minecraft " + minecraft + " yet");
    }

    /**
     * Runs a Forge or NeoForge installer for the client, headless, into the
     * game folder: it patches the game and writes the version it installs.
     */
    private static String installer(Path game, String id, String url, Path java,
            studios.milkdromeda.octo.installer.ClientInstaller.Log log) throws IOException {
        if (Files.isRegularFile(VersionProfile.jsonFile(game, id))) {
            return id;
        }

        Path work = game.resolve("instances").resolve(".installers");
        Files.createDirectories(work);
        Path jar = work.resolve(id + "-installer.jar");
        log.info("downloading " + id + "'s installer");
        Net.download(url, jar, null, new java.util.concurrent.atomic.AtomicLong());

        // The installers add a profile to the official launcher's list, and
        // refuse to run without one.
        Path profiles = game.resolve("launcher_profiles.json");

        if (!Files.isRegularFile(profiles)) {
            Files.writeString(profiles, "{\"profiles\":{}}\n", StandardCharsets.UTF_8);
        }

        log.info("installing " + id);
        Path output = work.resolve(id + "-install.log");
        Process process = new ProcessBuilder(java.toString(), "-jar", jar.toAbsolutePath().toString(),
                "--installClient", game.toAbsolutePath().toString()).directory(work.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();

        try {
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException(id + "'s installer took too long; its output is in " + output);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("installing " + id + " was interrupted", e);
        }

        if (process.exitValue() != 0 || !Files.isRegularFile(VersionProfile.jsonFile(game, id))) {
            throw new IOException(id + "'s installer failed (code " + process.exitValue() + "); its output is in "
                    + output);
        }

        Files.deleteIfExists(jar);
        return id;
    }

    /** Dotted versions by their numbers: 26.3.0.101 after 26.3.0.99. */
    static int compareVersions(String a, String b) {
        String[] left = a.split("[.-]");
        String[] right = b.split("[.-]");

        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            String l = i < left.length ? left[i] : "0";
            String r = i < right.length ? right[i] : "0";
            int compared = l.matches("\\d+") && r.matches("\\d+")
                    ? Long.compare(Long.parseLong(l), Long.parseLong(r))
                    : l.toLowerCase(Locale.ROOT).compareTo(r.toLowerCase(Locale.ROOT));

            if (compared != 0) {
                return compared;
            }
        }

        return 0;
    }
}
