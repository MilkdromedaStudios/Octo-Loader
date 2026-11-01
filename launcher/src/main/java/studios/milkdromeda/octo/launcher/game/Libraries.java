package studios.milkdromeda.octo.launcher.game;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** The libraries a version needs on this system, where each lives and where it comes from. */
public final class Libraries {
    /** One file: where it goes, and where to download it, if it can be. */
    public record Artifact(String name, Path path, String url, String sha1, long size) {
        public boolean downloadable() {
            return url != null && !url.isBlank();
        }
    }

    /** A library of natives packed in a jar, to unpack before the game starts (before 1.19). */
    public record Natives(Artifact jar, List<String> exclude) {
    }

    private Libraries() {
    }

    /**
     * The jars for the class path, in order. Of two with the same group,
     * name and classifier, the first wins: a loader's newer ASM over the
     * game's own.
     */
    public static List<Artifact> classpath(VersionProfile profile, Path libraries, Rules.Platform platform) {
        Map<String, Artifact> out = new LinkedHashMap<>();

        for (JsonObject library : profile.libraries) {
            if (!Rules.allows(library.get("rules"), platform, Map.of()) || library.get("natives") != null
                    && !(library.get("downloads") instanceof JsonObject downloads && downloads.has("artifact"))) {
                continue;
            }

            String name = Rules.string(library, "name");

            if (name == null) {
                continue;
            }

            Artifact artifact = artifact(library, name, libraries);

            if (artifact != null) {
                out.putIfAbsent(key(name), artifact);
            }
        }

        return new ArrayList<>(out.values());
    }

    /** The jars of natives to unpack (versions before 1.19 keep them apart this way). */
    public static List<Natives> natives(VersionProfile profile, Path libraries, Rules.Platform platform) {
        List<Natives> out = new ArrayList<>();

        for (JsonObject library : profile.libraries) {
            if (!(library.get("natives") instanceof JsonObject natives)
                    || !Rules.allows(library.get("rules"), platform, Map.of())) {
                continue;
            }

            String classifier = Rules.string(natives, platform.os());

            if (classifier == null) {
                continue;
            }

            classifier = classifier.replace("${arch}", platform.is64Bit() ? "64" : "32");
            String name = Rules.string(library, "name");
            JsonObject download = library.get("downloads") instanceof JsonObject downloads
                    && downloads.get("classifiers") instanceof JsonObject classifiers
                    && classifiers.get(classifier) instanceof JsonObject found ? found : null;
            Artifact jar;

            if (download != null) {
                jar = fromDownload(name + ":" + classifier, download, libraries);
            } else {
                String path = path(name + ":" + classifier);
                String base = Rules.string(library, "url");
                jar = new Artifact(name, libraries.resolve(path), (base == null ? MOJANG : slash(base)) + path, null,
                        0);
            }

            List<String> exclude = new ArrayList<>();

            if (library.get("extract") instanceof JsonObject extract && extract.get("exclude") instanceof JsonArray list) {
                for (JsonElement element : list) {
                    exclude.add(element.getAsString());
                }
            }

            out.add(new Natives(jar, exclude));
        }

        return out;
    }

    static final String MOJANG = "https://libraries.minecraft.net/";

    private static Artifact artifact(JsonObject library, String name, Path libraries) {
        if (library.get("downloads") instanceof JsonObject downloads) {
            return downloads.get("artifact") instanceof JsonObject artifact ? fromDownload(name, artifact, libraries)
                    : null;
        }

        // A library named only: Maven's layout, from the repository given or
        // Mojang's. Octo's own loader is put in place by the installer.
        String path = path(name);
        String base = Rules.string(library, "url");
        return new Artifact(name, libraries.resolve(path), base == null ? null : slash(base) + path,
                Rules.string(library, "sha1"), library.get("size") != null ? library.get("size").getAsLong() : 0);
    }

    private static Artifact fromDownload(String name, JsonObject download, Path libraries) {
        String path = Rules.string(download, "path");
        return new Artifact(name, libraries.resolve(path != null ? path : path(name)), Rules.string(download, "url"),
                Rules.string(download, "sha1"), download.get("size") != null ? download.get("size").getAsLong() : 0);
    }

    private static String slash(String base) {
        return base.endsWith("/") ? base : base + "/";
    }

    /** {@code group:name:version[:classifier][@extension]} to Maven's path. */
    public static String path(String name) {
        String extension = "jar";
        int at = name.indexOf('@');

        if (at >= 0) {
            extension = name.substring(at + 1);
            name = name.substring(0, at);
        }

        String[] parts = name.split(":");

        if (parts.length < 3) {
            throw new IllegalArgumentException("not a library name: " + name);
        }

        String file = parts[1] + "-" + parts[2] + (parts.length > 3 ? "-" + parts[3] : "") + "." + extension;
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + file;
    }

    /** What makes two libraries the same one, whatever their versions: group, name, classifier. */
    static String key(String name) {
        String[] parts = name.split("@")[0].split(":");
        return parts[0] + ":" + (parts.length > 1 ? parts[1] : "") + (parts.length > 3 ? ":" + parts[3] : "");
    }
}
