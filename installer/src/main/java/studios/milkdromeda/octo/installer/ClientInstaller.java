package studios.milkdromeda.octo.installer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Installs Octo into a Minecraft client.
 *
 * <p>The result is a version profile that inherits from the vanilla version the
 * player already has, so nothing about their existing installation is touched:
 * the vanilla jar, assets and libraries are reused, and the only additions are
 * one jar under {@code libraries/} and one profile under {@code versions/}.
 * Removing the profile removes Octo completely.
 */
public final class ClientInstaller {
    public static final String GROUP = "studios.milkdromeda";
    public static final String ARTIFACT = "octo-loader";

    private final Path gameDirectory;
    private final String loaderVersion;
    private final Log log;

    /** Where progress is reported: the console, or the window. */
    public interface Log {
        void info(String message);
    }

    public ClientInstaller(Path gameDirectory, String loaderVersion, Log log) {
        this.gameDirectory = gameDirectory;
        this.loaderVersion = loaderVersion;
        this.log = log;
    }

    /**
     * @param minecraftVersion the installed vanilla version to sit on top of
     * @return the id of the profile that was written
     */
    public String install(String minecraftVersion) throws IOException {
        Path vanillaJson = gameDirectory.resolve("versions").resolve(minecraftVersion)
                .resolve(minecraftVersion + ".json");

        if (!Files.isRegularFile(vanillaJson)) {
            throw new IOException("Minecraft " + minecraftVersion + " is not installed in " + gameDirectory
                    + ". Run it once in the launcher first, then install Octo.");
        }

        Path library = MinecraftPaths.libraryPath(gameDirectory, GROUP, ARTIFACT, loaderVersion);
        EmbeddedLoader.extractTo(library);
        log.info("installed loader to " + gameDirectory.relativize(library));

        String versionId = ARTIFACT + "-" + minecraftVersion;
        Path versionDirectory = gameDirectory.resolve("versions").resolve(versionId);
        Files.createDirectories(versionDirectory);

        Path versionJson = versionDirectory.resolve(versionId + ".json");
        Files.writeString(versionJson, buildVersionJson(versionId, minecraftVersion), StandardCharsets.UTF_8);
        log.info("wrote profile " + gameDirectory.relativize(versionJson));

        // Some launchers insist on a jar next to the json even when the profile
        // inherits one; an empty jar satisfies them and is never read.
        Path versionJar = versionDirectory.resolve(versionId + ".jar");

        if (!Files.exists(versionJar)) {
            Files.write(versionJar, emptyJar());
        }

        registerProfile(versionId, minecraftVersion);
        Files.createDirectories(gameDirectory.resolve("mods"));
        return versionId;
    }

    private String buildVersionJson(String versionId, String minecraftVersion) {
        JsonObject root = new JsonObject();
        root.addProperty("id", versionId);
        root.addProperty("inheritsFrom", minecraftVersion);
        root.addProperty("type", "release");
        root.addProperty("mainClass", "studios.milkdromeda.octo.launch.OctoMain");
        root.addProperty("time", Instant.now().toString());
        root.addProperty("releaseTime", Instant.now().toString());

        JsonObject arguments = new JsonObject();
        arguments.add("game", new JsonArray());
        arguments.add("jvm", new JsonArray());
        root.add("arguments", arguments);

        JsonArray libraries = new JsonArray();
        JsonObject loader = new JsonObject();
        loader.addProperty("name", GROUP + ":" + ARTIFACT + ":" + loaderVersion);
        libraries.add(loader);
        root.add("libraries", libraries);

        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
    }

    /**
     * Adds an entry to {@code launcher_profiles.json} so the profile is visible
     * in the launcher without the player creating one by hand. A launcher that
     * keeps its profiles elsewhere simply gets no entry, and the version is
     * still selectable.
     */
    private void registerProfile(String versionId, String minecraftVersion) {
        Path profilesFile = gameDirectory.resolve("launcher_profiles.json");

        if (!Files.isRegularFile(profilesFile)) {
            log.info("no launcher_profiles.json here; select \"" + versionId + "\" in your launcher manually");
            return;
        }

        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonElement parsed = JsonParser.parseString(Files.readString(profilesFile, StandardCharsets.UTF_8));

            if (!parsed.isJsonObject()) {
                return;
            }

            JsonObject root = parsed.getAsJsonObject();
            JsonObject profiles = root.has("profiles") && root.get("profiles").isJsonObject()
                    ? root.getAsJsonObject("profiles") : new JsonObject();

            JsonObject profile = new JsonObject();
            profile.addProperty("name", "Octo Loader " + minecraftVersion);
            profile.addProperty("type", "custom");
            profile.addProperty("created", Instant.now().toString());
            profile.addProperty("lastVersionId", versionId);
            profile.addProperty("icon", "Furnace");

            profiles.add("octo-loader-" + minecraftVersion, profile);
            root.add("profiles", profiles);

            Files.writeString(profilesFile, gson.toJson(root) + "\n", StandardCharsets.UTF_8);
            log.info("added a launcher profile named \"Octo Loader " + minecraftVersion + "\"");
        } catch (IOException | RuntimeException e) {
            log.info("could not update launcher_profiles.json (" + e + "); select \"" + versionId
                    + "\" manually in your launcher");
        }
    }

    /** Removes the profile and the installed loader jar. */
    public void uninstall(String minecraftVersion) throws IOException {
        String versionId = ARTIFACT + "-" + minecraftVersion;
        Path versionDirectory = gameDirectory.resolve("versions").resolve(versionId);

        if (Files.isDirectory(versionDirectory)) {
            try (var stream = Files.walk(versionDirectory)) {
                for (Path path : stream.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList()) {
                    Files.deleteIfExists(path);
                }
            }

            log.info("removed " + gameDirectory.relativize(versionDirectory));
        }

        Path library = MinecraftPaths.libraryPath(gameDirectory, GROUP, ARTIFACT, loaderVersion);

        if (Files.deleteIfExists(library)) {
            log.info("removed " + gameDirectory.relativize(library));
        }
    }

    /** The 22 bytes of an empty zip. */
    static byte[] emptyJar() {
        return new byte[] {
                0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        };
    }

    public static List<String> installableVersions(Path gameDirectory) {
        return MinecraftPaths.installedVersions(gameDirectory).stream()
                .filter(id -> !id.startsWith(ARTIFACT + "-"))
                .toList();
    }
}
