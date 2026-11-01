package studios.milkdromeda.octo.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import studios.milkdromeda.octo.installer.ClientInstaller;

/**
 * The Minecraft versions there are, from Mojang's own list — the one the
 * official launcher reads — and which of them are downloaded and which have
 * an Octo installation.
 */
public final class Versions {
    static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /** One version of the game. */
    public record Version(String id, String type, String releaseTime, boolean downloaded, boolean withOcto) {
        public boolean isRelease() {
            return type.equals("release");
        }
    }

    private Versions() {
    }

    /**
     * Every version, newest first. Read from Mojang when it can be and kept,
     * so the list is there offline too; the downloaded ones are listed even
     * when neither is.
     */
    public static List<Version> list(Path gameDirectory, boolean snapshots) {
        JsonObject manifest = manifest();
        Set<String> downloaded = new TreeSet<>(ClientInstaller.installableVersions(gameDirectory));
        Set<String> withOcto = octoInstallations(gameDirectory);
        List<Version> out = new ArrayList<>();
        Set<String> seen = new TreeSet<>();

        if (manifest != null && manifest.get("versions") != null) {
            for (JsonElement element : manifest.getAsJsonArray("versions")) {
                JsonObject version = element.getAsJsonObject();
                String id = version.get("id").getAsString();
                String type = version.get("type").getAsString();

                if (!snapshots && !type.equals("release") && !downloaded.contains(id)) {
                    continue;
                }

                if (type.startsWith("old_")) {
                    continue;
                }

                seen.add(id);
                out.add(new Version(id, type, version.get("releaseTime").getAsString(), downloaded.contains(id),
                        withOcto.contains(id)));
            }
        }

        for (String id : downloaded) {
            if (!seen.contains(id)) {
                out.add(new Version(id, "release", "", true, withOcto.contains(id)));
            }
        }

        return out;
    }

    /** The newest release, as Mojang lists it. */
    public static String latestRelease() {
        JsonObject manifest = manifest();
        return manifest != null && manifest.get("latest") instanceof JsonObject latest
                ? latest.get("release").getAsString() : null;
    }

    /** The Minecraft versions with an {@code octo-loader-<version>} profile in this folder. */
    public static Set<String> octoInstallations(Path gameDirectory) {
        Set<String> out = new TreeSet<>();
        Path versions = gameDirectory.resolve("versions");

        if (!Files.isDirectory(versions)) {
            return out;
        }

        try (var entries = Files.list(versions)) {
            entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(ClientInstaller.ARTIFACT + "-"))
                    .forEach(name -> out.add(name.substring(ClientInstaller.ARTIFACT.length() + 1)));
        } catch (IOException e) {
            // None, then.
        }

        return out;
    }

    private static JsonObject manifest() {
        Path cache = LauncherSettings.home().resolve("version_manifest_v2.json");

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(MANIFEST))
                    .timeout(Duration.ofSeconds(15)).header("User-Agent", Launcher.userAgent()).build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject parsed = JsonParser.parseString(response.body()).getAsJsonObject();
                Files.createDirectories(cache.getParent());
                Files.writeString(cache, response.body(), StandardCharsets.UTF_8);
                return parsed;
            }
        } catch (IOException | RuntimeException e) {
            // Offline: the copy from last time.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            return Files.isRegularFile(cache)
                    ? JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8)).getAsJsonObject()
                    : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
