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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** What is new: the project's own releases, newest first, kept for when there is no connection. */
public final class News {
    /** One release. */
    public record Item(String title, String summary, Instant published, String url) {
    }

    private News() {
    }

    public static List<Item> latest(int count) {
        Path cache = LauncherSettings.home().resolve("news.json");
        String body = null;

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(
                    "https://api.github.com/repos/" + Updates.REPOSITORY + "/releases?per_page=" + count))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json")
                    .header("User-Agent", Launcher.userAgent()).build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                body = response.body();
                Files.createDirectories(cache.getParent());
                Files.writeString(cache, body, StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            // Offline: last time's.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        try {
            if (body == null && Files.isRegularFile(cache)) {
                body = Files.readString(cache, StandardCharsets.UTF_8);
            }

            return body == null ? List.of() : parse(body, count);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    static List<Item> parse(String body, int count) {
        List<Item> out = new ArrayList<>();

        for (JsonElement element : JsonParser.parseString(body).getAsJsonArray()) {
            JsonObject release = element.getAsJsonObject();
            String name = text(release, "name");
            String tag = text(release, "tag_name");
            String notes = text(release, "body");
            out.add(new Item(title(name == null || name.isBlank() ? tag : name), summary(notes),
                    Instant.parse(text(release, "published_at")), text(release, "html_url")));

            if (out.size() >= count) {
                break;
            }
        }

        return out;
    }

    /** "Octo Loader Build f018971…" for a build named after its whole commit. */
    public static String title(String name) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)^(.*?)\\bbuild[- ]([0-9a-f]{12,40})$")
                .matcher(name.strip());
        return matcher.matches() ? matcher.group(1).strip() + " build " + matcher.group(2).substring(0, 7) : name;
    }

    /** The first sentence or so of the release notes, without Markdown. */
    static String summary(String notes) {
        if (notes == null || notes.isBlank()) {
            return "A new build of Octo Loader, the installer and Octo Launcher.";
        }

        for (String line : notes.split("\\R")) {
            String plain = line.replaceAll("[#*`>_\\[\\]]", "").replaceAll("\\(http[^)]*\\)", "").strip();

            if (plain.length() > 20) {
                return plain.length() > 140 ? plain.substring(0, 137) + "..." : plain;
            }
        }

        return "A new build of Octo Loader, the installer and Octo Launcher.";
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
