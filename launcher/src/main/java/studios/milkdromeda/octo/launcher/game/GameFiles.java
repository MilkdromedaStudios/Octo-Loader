package studios.milkdromeda.octo.launcher.game;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Downloads Minecraft as the official launcher lays it out: the version's
 * JSON and jar under {@code versions}, libraries under {@code libraries},
 * assets under {@code assets}. Every file is checked against the SHA-1
 * Mojang publishes with it, and a file already there is not downloaded again.
 */
public final class GameFiles {
    static final String RESOURCES = "https://resources.download.minecraft.net/";
    private static final int PARALLEL = 8;

    /** How far a long job has got: a sentence for the player, and bytes done of bytes in all. */
    public interface Progress {
        void update(String stage, long done, long total);

        Progress NONE = (stage, done, total) -> { };
    }

    /** A file to fetch. */
    record Download(String url, Path path, String sha1, long size) {
    }

    private GameFiles() {
    }

    /**
     * Makes sure every version in the chain has its JSON, downloading those
     * Mojang lists (or a newer edition of one, when Mojang has republished it).
     *
     * @param manifest finds a version in Mojang's list: its entry, or {@code null}
     */
    public static void versionFiles(Path gameDirectory, String id, Function<String, JsonObject> manifest)
            throws IOException {
        String next = id;
        int depth = 0;

        while (next != null && depth++ < 16) {
            Path file = VersionProfile.jsonFile(gameDirectory, next);
            JsonObject entry = manifest.apply(next);

            if (entry != null) {
                String sha1 = Rules.string(entry, "sha1");

                if (!Net.intact(file, 0, sha1, true)) {
                    Net.download(Rules.string(entry, "url"), file, sha1, null);
                }
            } else if (!Files.isRegularFile(file)) {
                throw new IOException("Minecraft " + next + " is not one Mojang lists, and it is not downloaded");
            }

            JsonObject json = Net.readJson(file);
            next = json == null ? null : Rules.string(json, "inheritsFrom");
        }
    }

    /**
     * Downloads what the version needs to run: its jar, libraries, assets and
     * logging setup.
     *
     * @param verify check the SHA-1 of every file already there, not only its size (repairs a damaged install)
     */
    public static void fetch(Path gameDirectory, VersionProfile profile, Rules.Platform platform, boolean verify,
            Progress progress) throws IOException {
        Path libraries = gameDirectory.resolve("libraries");
        Map<Path, Download> wanted = new LinkedHashMap<>();
        progress.update("Checking the game files", 0, 0);

        if (profile.clientDownload != null) {
            add(wanted, new Download(Rules.string(profile.clientDownload, "url"), profile.jar(gameDirectory),
                    Rules.string(profile.clientDownload, "sha1"), size(profile.clientDownload)), verify);
        } else if (!Files.isRegularFile(profile.jar(gameDirectory))) {
            throw new IOException("the game's jar " + profile.jar(gameDirectory) + " is missing and has no download");
        }

        for (Libraries.Artifact artifact : Libraries.classpath(profile, libraries, platform)) {
            library(wanted, artifact, verify);
        }

        for (Libraries.Natives natives : Libraries.natives(profile, libraries, platform)) {
            library(wanted, natives.jar(), verify);
        }

        Path assets = gameDirectory.resolve("assets");
        JsonObject index = null;

        if (profile.assetIndex != null) {
            Path indexFile = assets.resolve("indexes").resolve(profile.assets + ".json");
            String sha1 = Rules.string(profile.assetIndex, "sha1");

            if (!Net.intact(indexFile, size(profile.assetIndex), sha1, true)) {
                Net.download(Rules.string(profile.assetIndex, "url"), indexFile, sha1, null);
            }

            index = Net.readJson(indexFile);

            if (index != null && index.get("objects") instanceof JsonObject objects) {
                for (Map.Entry<String, JsonElement> object : objects.entrySet()) {
                    JsonObject value = object.getValue().getAsJsonObject();
                    String hash = Rules.string(value, "hash");
                    add(wanted, new Download(RESOURCES + hash.substring(0, 2) + "/" + hash,
                            assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash), hash,
                            size(value)), verify);
                }
            }
        }

        if (profile.logging != null && profile.logging.get("client") instanceof JsonObject client
                && client.get("file") instanceof JsonObject file) {
            add(wanted, new Download(Rules.string(file, "url"), logConfig(gameDirectory, profile), Rules.string(file,
                    "sha1"), size(file)), verify);
        }

        download(new ArrayList<>(wanted.values()), progress);

        if (index != null) {
            placeLegacyAssets(gameDirectory, profile, index);
        }
    }

    private static void library(Map<Path, Download> wanted, Libraries.Artifact artifact, boolean verify)
            throws IOException {
        if (artifact.downloadable()) {
            add(wanted, new Download(artifact.url(), artifact.path(), artifact.sha1(), artifact.size()), verify);
        } else if (!Files.isRegularFile(artifact.path())) {
            throw new IOException("the library " + artifact.name() + " is missing, and there is nowhere to download "
                    + "it from");
        }
    }

    private static void add(Map<Path, Download> wanted, Download download, boolean verify) {
        if (download.url() != null && !Net.intact(download.path(), download.size(), download.sha1(), verify)) {
            wanted.putIfAbsent(download.path(), download);
        }
    }

    private static long size(JsonObject object) {
        return object.get("size") != null ? object.get("size").getAsLong() : 0;
    }

    /** Downloads the files, several at a time, telling {@code progress} as the bytes come in. */
    static void download(List<Download> downloads, Progress progress) throws IOException {
        if (downloads.isEmpty()) {
            return;
        }

        long total = downloads.stream().mapToLong(Download::size).sum();
        AtomicLong done = new AtomicLong();
        String stage = "Downloading " + downloads.size() + (downloads.size() == 1 ? " file" : " files");
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL, runnable -> {
            Thread thread = new Thread(runnable, "octo-download");
            thread.setDaemon(true);
            return thread;
        });
        List<Future<?>> running = new ArrayList<>();

        try {
            for (Download download : downloads) {
                running.add(pool.submit(() -> {
                    Net.download(download.url(), download.path(), download.sha1(), done);
                    return null;
                }));
            }

            pool.shutdown();

            while (!pool.awaitTermination(150, TimeUnit.MILLISECONDS)) {
                progress.update(stage, Math.min(done.get(), total), total);
            }

            progress.update(stage, total, total);

            for (Future<?> future : running) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Where the version's logging setup is kept. */
    public static Path logConfig(Path gameDirectory, VersionProfile profile) {
        JsonObject file = profile.logging.getAsJsonObject("client").getAsJsonObject("file");
        return gameDirectory.resolve("assets").resolve("log_configs").resolve(Rules.string(file, "id"));
    }

    /** Where very old versions read assets from: by name, not by hash. */
    public static Path virtualAssets(Path gameDirectory, VersionProfile profile) {
        return gameDirectory.resolve("assets").resolve("virtual").resolve(profile.assets);
    }

    /** Versions before 1.7.3 read their assets by name; they are copied out for them. */
    private static void placeLegacyAssets(Path gameDirectory, VersionProfile profile, JsonObject index)
            throws IOException {
        boolean virtual = index.get("virtual") != null && index.get("virtual").getAsBoolean();
        boolean resources = index.get("map_to_resources") != null && index.get("map_to_resources").getAsBoolean();

        if (!virtual && !resources) {
            return;
        }

        Path into = resources ? gameDirectory.resolve("resources") : virtualAssets(gameDirectory, profile);
        Path objects = gameDirectory.resolve("assets").resolve("objects");

        for (Map.Entry<String, JsonElement> object : index.getAsJsonObject("objects").entrySet()) {
            String hash = Rules.string(object.getValue().getAsJsonObject(), "hash");
            Path target = into.resolve(object.getKey()).normalize();

            if (!target.startsWith(into) || Files.isRegularFile(target)) {
                continue;
            }

            Files.createDirectories(target.getParent());
            Files.copy(objects.resolve(hash.substring(0, 2)).resolve(hash), target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Unpacks the natives of versions before 1.19 into a folder of their own,
     * fresh each launch. Newer versions load theirs from the class path and
     * need nothing here.
     */
    public static Path unpackNatives(Path gameDirectory, VersionProfile profile, Rules.Platform platform)
            throws IOException {
        Path folder = gameDirectory.resolve("versions").resolve(profile.id).resolve("natives");
        List<Libraries.Natives> natives = Libraries.natives(profile, gameDirectory.resolve("libraries"), platform);
        Files.createDirectories(folder);

        for (Libraries.Natives jar : natives) {
            try (ZipFile zip = new ZipFile(jar.jar().path().toFile())) {
                for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                    String name = entry.getName();

                    if (entry.isDirectory() || jar.exclude().stream().anyMatch(name::startsWith)) {
                        continue;
                    }

                    Path target = folder.resolve(name).normalize();

                    // Outside the folder, or already there as it is in the jar (on Windows a
                    // library a running game holds cannot be written over).
                    if (!target.startsWith(folder) || Files.isRegularFile(target) && entry.getSize() >= 0
                            && Files.size(target) == entry.getSize()) {
                        continue;
                    }

                    Files.createDirectories(target.getParent());

                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        return folder;
    }

    /** Writes text through a part file, so a reader never sees half of it. */
    static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(part, text, StandardCharsets.UTF_8);
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
