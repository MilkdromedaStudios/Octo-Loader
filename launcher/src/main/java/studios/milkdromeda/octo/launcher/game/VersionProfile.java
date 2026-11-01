package studios.milkdromeda.octo.launcher.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A version as it is launched: its own JSON with everything it inherits
 * folded in, the way the official launcher folds an {@code inheritsFrom}
 * chain. The child's libraries come first and its main class wins; its
 * arguments go after the parent's.
 */
public final class VersionProfile {
    public final String id;
    /** The version whose jar is the game: the first in the chain that has a client download. */
    public final String jarVersion;
    public final JsonObject clientDownload;
    public final String mainClass;
    public final String type;
    public final String assets;
    public final JsonObject assetIndex;
    public final JsonObject javaVersion;
    public final JsonObject logging;
    public final List<JsonObject> libraries;
    public final List<JsonElement> gameArguments;
    public final List<JsonElement> jvmArguments;
    /** The JVM options the game suggests when the player has set none (memory, the collector). */
    public final List<JsonElement> defaultJvmArguments;
    /** Before 1.13: the game's arguments as one string, with no JVM ones. */
    public final String legacyArguments;

    private VersionProfile(String id, String jarVersion, JsonObject clientDownload, String mainClass, String type,
            String assets, JsonObject assetIndex, JsonObject javaVersion, JsonObject logging,
            List<JsonObject> libraries, List<JsonElement> gameArguments, List<JsonElement> jvmArguments,
            List<JsonElement> defaultJvmArguments, String legacyArguments) {
        this.id = id;
        this.jarVersion = jarVersion;
        this.clientDownload = clientDownload;
        this.mainClass = mainClass;
        this.type = type;
        this.assets = assets;
        this.assetIndex = assetIndex;
        this.javaVersion = javaVersion;
        this.logging = logging;
        this.libraries = Collections.unmodifiableList(libraries);
        this.gameArguments = Collections.unmodifiableList(gameArguments);
        this.jvmArguments = Collections.unmodifiableList(jvmArguments);
        this.defaultJvmArguments = Collections.unmodifiableList(defaultJvmArguments);
        this.legacyArguments = legacyArguments;
    }

    public static Path jsonFile(Path gameDirectory, String id) {
        return gameDirectory.resolve("versions").resolve(id).resolve(id + ".json");
    }

    /** The chain of version ids, the launched one first. */
    public static List<String> chain(Path gameDirectory, String id) throws IOException {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String next = id;

        while (next != null) {
            if (!seen.add(next)) {
                throw new IOException("version " + id + " inherits from itself through " + next);
            }

            out.add(next);
            JsonObject json = Net.readJson(jsonFile(gameDirectory, next));

            if (json == null) {
                break;
            }

            next = Rules.string(json, "inheritsFrom");
        }

        return out;
    }

    public static VersionProfile load(Path gameDirectory, String id) throws IOException {
        List<JsonObject> chain = new ArrayList<>();

        for (String link : chain(gameDirectory, id)) {
            Path file = jsonFile(gameDirectory, link);

            if (!Files.isRegularFile(file)) {
                throw new IOException("Minecraft " + link + " is not downloaded yet (no " + file + ")");
            }

            JsonObject json = Net.readJson(file);

            if (json == null) {
                throw new IOException(file + " is not a version file");
            }

            chain.add(json);
        }

        String mainClass = null;
        String type = null;
        String assets = null;
        JsonObject assetIndex = null;
        JsonObject javaVersion = null;
        JsonObject logging = null;
        String legacy = null;
        String jarVersion = null;
        JsonObject client = null;
        List<JsonObject> libraries = new ArrayList<>();
        List<JsonElement> game = new ArrayList<>();
        List<JsonElement> jvm = new ArrayList<>();
        List<JsonElement> defaults = new ArrayList<>();

        // The child first: the first value found is the one that counts.
        for (JsonObject json : chain) {
            mainClass = mainClass != null ? mainClass : Rules.string(json, "mainClass");
            type = type != null ? type : Rules.string(json, "type");
            legacy = legacy != null ? legacy : Rules.string(json, "minecraftArguments");

            if (assetIndex == null && json.get("assetIndex") instanceof JsonObject index) {
                assetIndex = index;
                assets = Rules.string(json, "assets");
            }

            javaVersion = javaVersion != null ? javaVersion
                    : json.get("javaVersion") instanceof JsonObject java ? java : null;
            logging = logging != null ? logging : json.get("logging") instanceof JsonObject log ? log : null;

            if (jarVersion == null && json.get("downloads") instanceof JsonObject downloads
                    && downloads.get("client") instanceof JsonObject download) {
                client = download;
                jarVersion = Rules.string(json, "jar") != null ? Rules.string(json, "jar") : Rules.string(json, "id");
            }

            if (json.get("libraries") instanceof JsonArray list) {
                for (JsonElement library : list) {
                    if (library instanceof JsonObject object) {
                        libraries.add(object);
                    }
                }
            }
        }

        // Arguments: the parent's first, then each child's after them.
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (chain.get(i).get("arguments") instanceof JsonObject arguments) {
                addAll(game, arguments.get("game"));
                addAll(jvm, arguments.get("jvm"));
                addAll(defaults, arguments.get("default-user-jvm"));
            }
        }

        if (assets == null && assetIndex != null) {
            assets = Rules.string(assetIndex, "id");
        }

        if (mainClass == null) {
            throw new IOException("version " + id + " names no main class");
        }

        if (jarVersion == null) {
            // A version with no download of its own: its jar sits beside its JSON.
            jarVersion = chain.size() > 1 ? Rules.string(chain.get(chain.size() - 1), "id") : id;
        }

        return new VersionProfile(id, jarVersion, client, mainClass, type == null ? "release" : type,
                assets == null ? "legacy" : assets, assetIndex, javaVersion, logging, libraries, game, jvm, defaults,
                legacy);
    }

    private static void addAll(List<JsonElement> into, JsonElement from) {
        if (from instanceof JsonArray array) {
            array.forEach(into::add);
        }
    }

    /** The game's jar. */
    public Path jar(Path gameDirectory) {
        return gameDirectory.resolve("versions").resolve(jarVersion).resolve(jarVersion + ".jar");
    }

    /** The major Java version the game asks for: 8 for versions too old to say. */
    public int javaMajor() {
        return javaVersion != null && javaVersion.get("majorVersion") != null
                ? javaVersion.get("majorVersion").getAsInt() : 8;
    }

    /** The oldest Java Octo Loader starts on. */
    public static final int OCTO_JAVA = 17;

    /**
     * Whether Octo Loader can run this version: not the versions on LWJGL 2
     * (1.12.2 and before), whose natives load only on Java 8, older than the
     * Java Octo needs.
     */
    public boolean supportsOcto() {
        for (JsonObject library : libraries) {
            String name = Rules.string(library, "name");

            if (name != null && name.startsWith("org.lwjgl.lwjgl:lwjgl:2.")) {
                return false;
            }
        }

        return true;
    }

    /**
     * Mojang's name for the Java runtime to start the game on: the one it
     * asks for, or with Octo, Mojang's Java 17 where it asks for one older
     * than Octo Loader starts on (1.13 to 1.17).
     */
    public String javaComponent(boolean withOcto) {
        String component = javaVersion == null ? null : Rules.string(javaVersion, "component");

        if (withOcto && javaMajor() < OCTO_JAVA) {
            return "java-runtime-gamma";
        }

        return component != null ? component : "jre-legacy";
    }
}
