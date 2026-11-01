package studios.milkdromeda.octo.launcher.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The Java each Minecraft version runs on: the runtimes Mojang builds for the
 * official launcher, downloaded file by file from Mojang's runtime list and
 * checked as they come, the same Java the game is tested with.
 */
public final class JavaRuntimes {
    static final String INDEX = "https://launchermeta.mojang.com/v1/products/java-runtime/"
            + "2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    private JavaRuntimes() {
    }

    /** Mojang's name for this system in the runtime list, or {@code null} where it builds none. */
    public static String platformKey(Rules.Platform platform) {
        return switch (platform.os()) {
            case "windows" -> platform.isArm() ? "windows-arm64" : platform.is64Bit() ? "windows-x64" : "windows-x86";
            case "osx" -> platform.isArm() ? "mac-os-arm64" : "mac-os";
            default -> platform.isArm() ? null : platform.is64Bit() ? "linux" : "linux-i386";
        };
    }

    /** Where a runtime is kept. */
    public static Path folder(Path runtimes, String component, Rules.Platform platform) {
        return runtimes.resolve(component).resolve(String.valueOf(platformKey(platform)));
    }

    /** The {@code java} to start the game with in a runtime folder. */
    public static Path executable(Path home, Rules.Platform platform) {
        if (platform.os().equals("osx")) {
            Path bundle = home.resolve("jre.bundle").resolve("Contents").resolve("Home").resolve("bin").resolve("java");

            if (Files.exists(bundle)) {
                return bundle;
            }
        }

        // javaw: no console window behind the game on Windows.
        return home.resolve("bin").resolve(platform.isWindows() ? "javaw.exe" : "java");
    }

    /**
     * The Java for a version, downloading it first if it is not here yet.
     *
     * @param runtimes the folder runtimes are kept in
     * @return the {@code java} program
     */
    public static Path ensure(Path runtimes, String component, Rules.Platform platform, boolean verify,
            GameFiles.Progress progress) throws IOException {
        String key = platformKey(platform);

        if (key == null) {
            throw new IOException("Mojang makes no Java for " + platform.os() + " on " + platform.arch()
                    + "; set a Java of your own in Settings");
        }

        Path home = folder(runtimes, component, platform);
        Path marker = home.resolve(".octo-runtime");
        Path java = executable(home, platform);

        if (!verify && Files.isRegularFile(marker) && Files.isRegularFile(java)) {
            return java;
        }

        progress.update("Getting Java for Minecraft", 0, 0);
        JsonObject index = Net.json(INDEX);
        JsonObject forPlatform = index.getAsJsonObject(key);
        JsonArray editions = forPlatform == null ? null : forPlatform.getAsJsonArray(component);

        if (editions == null || editions.isEmpty()) {
            throw new IOException("Mojang has no " + component + " Java for " + key);
        }

        JsonObject edition = editions.get(0).getAsJsonObject();
        JsonObject manifestRef = edition.getAsJsonObject("manifest");
        String version = edition.getAsJsonObject("version").get("name").getAsString();
        Path manifestFile = runtimes.resolve(component + "-" + key + ".json");

        if (!Net.intact(manifestFile, 0, Rules.string(manifestRef, "sha1"), true)) {
            Net.download(Rules.string(manifestRef, "url"), manifestFile, Rules.string(manifestRef, "sha1"), null);
        }

        JsonObject manifest = Net.readJson(manifestFile);

        if (manifest == null || !(manifest.get("files") instanceof JsonObject files)) {
            throw new IOException("Mojang's file list for " + component + " could not be read");
        }

        List<GameFiles.Download> downloads = new ArrayList<>();
        List<Path> executables = new ArrayList<>();
        List<Map.Entry<Path, String>> links = new ArrayList<>();

        for (Map.Entry<String, JsonElement> entry : files.entrySet()) {
            JsonObject file = entry.getValue().getAsJsonObject();
            Path target = home.resolve(entry.getKey()).normalize();

            if (!target.startsWith(home)) {
                continue;
            }

            switch (String.valueOf(Rules.string(file, "type"))) {
                case "directory" -> Files.createDirectories(target);
                case "link" -> links.add(Map.entry(target, Rules.string(file, "target")));
                case "file" -> {
                    JsonObject raw = file.getAsJsonObject("downloads").getAsJsonObject("raw");
                    long size = raw.get("size").getAsLong();
                    String sha1 = Rules.string(raw, "sha1");

                    if (!Net.intact(target, size, sha1, verify)) {
                        downloads.add(new GameFiles.Download(Rules.string(raw, "url"), target, sha1, size));
                    }

                    if (file.get("executable") != null && file.get("executable").getAsBoolean()) {
                        executables.add(target);
                    }
                }
                default -> {
                    // Nothing else is listed.
                }
            }
        }

        GameFiles.Progress named = (stage, done, total) -> progress.update("Downloading Java " + version, done,
                total);
        GameFiles.download(downloads, named);

        if (!platform.isWindows()) {
            Set<PosixFilePermission> runnable = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);

            for (Path file : executables) {
                try {
                    Files.setPosixFilePermissions(file, runnable);
                } catch (UnsupportedOperationException e) {
                    file.toFile().setExecutable(true);
                }
            }

            for (Map.Entry<Path, String> link : links) {
                Path target = link.getKey();
                Path pointsTo = target.getParent().resolve(link.getValue()).normalize();

                if (!pointsTo.startsWith(home)) {
                    continue;
                }

                Files.createDirectories(target.getParent());
                Files.deleteIfExists(target);
                Files.createSymbolicLink(target, target.getParent().relativize(pointsTo));
            }
        }

        if (!Files.isRegularFile(java)) {
            throw new IOException("the downloaded Java has no " + home.relativize(java));
        }

        GameFiles.write(marker, version + "\n");
        return java;
    }
}
