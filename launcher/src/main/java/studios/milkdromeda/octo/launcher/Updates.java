package studios.milkdromeda.octo.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import studios.milkdromeda.octo.installer.ClientInstaller;
import studios.milkdromeda.octo.installer.Installer;

/**
 * Keeping Octo Launcher and Octo Loader current from the project's releases.
 *
 * <p>Every file is checked against the release's {@code checksums.txt}
 * (SHA-256) before it is used; a download that does not match is thrown away.
 * The launcher cannot replace the jar it is running from, so a new one is
 * staged and swapped in by a short-lived copy of itself once this one has
 * exited, then started again.
 */
public final class Updates {
    static final String REPOSITORY = "MilkdromedaStudios/Octo-Loader";
    static final String LATEST = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    static final String LAUNCHER_ASSET = "octo-launcher.jar";

    /** The newest release, and its files. */
    public record Release(String tag, String name, String launcherUrl, String loaderUrl, String loaderName,
            Map<String, String> checksums) {
    }

    private Updates() {
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** The newest release, or {@code null} when it cannot be reached. */
    public static Release latest() throws IOException, InterruptedException {
        HttpResponse<String> response = client().send(HttpRequest.newBuilder(URI.create(LATEST))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/vnd.github+json")
                .header("User-Agent", Launcher.userAgent()).build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("GitHub answered " + response.statusCode());
        }

        JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
        String launcher = null;
        String loader = null;
        String loaderName = null;
        String checksums = null;

        for (JsonElement element : release.getAsJsonArray("assets")) {
            JsonObject asset = element.getAsJsonObject();
            String name = asset.get("name").getAsString();
            String url = asset.get("browser_download_url").getAsString();

            if (name.equals(LAUNCHER_ASSET)) {
                launcher = url;
            } else if (name.startsWith("octo-loader-") && name.endsWith(".jar")) {
                loader = url;
                loaderName = name;
            } else if (name.equals("checksums.txt")) {
                checksums = url;
            }
        }

        Map<String, String> sums = checksums == null ? Map.of() : parseChecksums(text(checksums));
        String tag = release.get("tag_name").getAsString();
        JsonElement name = release.get("name");
        String title = name != null && !name.isJsonNull() ? name.getAsString() : tag;
        return new Release(tag, title, launcher, loader, loaderName, sums);
    }

    static Map<String, String> parseChecksums(String text) {
        Map<String, String> out = new HashMap<>();

        for (String line : text.split("\\R")) {
            String[] parts = line.strip().split("\\s+\\*?", 2);

            if (parts.length == 2 && parts[0].matches("[0-9a-fA-F]{64}")) {
                out.put(parts[1].strip(), parts[0].toLowerCase(java.util.Locale.ROOT));
            }
        }

        return out;
    }

    private static String text(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = client().send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30)).header("User-Agent", Launcher.userAgent()).build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException(url + " answered " + response.statusCode());
        }

        return response.body();
    }

    /** Whether a release is newer than this launcher: a different build, when this one is a released build. */
    public static boolean isNewer(Release release) {
        String own = Launcher.build();
        return release != null && !own.equals("dev") && !own.equals(release.tag());
    }

    /** Downloads a release file and checks it; nothing is left behind if it does not match. */
    public static Path download(String url, String name, Map<String, String> checksums, Path target)
            throws IOException, InterruptedException {
        String expected = checksums.get(name);

        if (expected == null) {
            throw new IOException("the release has no checksum for " + name + ", so it is not used");
        }

        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<InputStream> response = client().send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5)).header("User-Agent", Launcher.userAgent()).build(),
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException(name + " answered " + response.statusCode());
        }

        try (InputStream in = response.body()) {
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
        }

        String actual = sha256(part);

        if (!actual.equalsIgnoreCase(expected)) {
            Files.deleteIfExists(part);
            throw new IOException(name + " did not match its checksum (expected " + expected + ", got " + actual
                    + "); it was discarded");
        }

        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];

            for (int read; (read = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }

            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // ------------------------------------------------------------- the app

    /** Where a downloaded launcher waits to be swapped in. */
    static Path staged() {
        return LauncherSettings.home().resolve("updates").resolve(LAUNCHER_ASSET);
    }

    /** Downloads the new launcher and leaves it ready for {@link #restartInto}. */
    public static Path stageLauncher(Release release) throws IOException, InterruptedException {
        if (release.launcherUrl() == null) {
            throw new IOException("the release " + release.tag() + " has no " + LAUNCHER_ASSET);
        }

        Path staged = download(release.launcherUrl(), LAUNCHER_ASSET, release.checksums(), staged());
        Files.writeString(staged.resolveSibling("staged-tag.txt"), release.tag(), StandardCharsets.UTF_8);
        return staged;
    }

    /** The jar this launcher runs from, or {@code null} when it is not a jar. */
    public static Path runningJar() {
        try {
            Path path = Path.of(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(path) && path.toString().endsWith(".jar") ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Starts the staged jar to put itself in place of this one once this one
     * has exited, and to start it again. The caller exits next.
     */
    public static void restartInto(Path staged) throws IOException {
        Path target = runningJar();

        if (target == null) {
            throw new IOException("this launcher is not running from a jar, so it cannot replace itself");
        }

        List<String> command = new ArrayList<>();
        command.add(java());
        command.add("-cp");
        command.add(staged.toString());
        command.add(Launcher.class.getName());
        command.add("--apply-update");
        command.add(staged.toString());
        command.add(target.toString());
        command.add(Long.toString(ProcessHandle.current().pid()));
        new ProcessBuilder(command).inheritIO().start();
    }

    /** Run by the staged jar: wait for the old launcher to exit, take its place, start it. */
    static void apply(Path staged, Path target, long pid) throws Exception {
        ProcessHandle.of(pid).ifPresent(process -> {
            try {
                process.onExit().get(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                // Waited long enough.
            }
        });

        IOException last = null;

        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                Path part = target.resolveSibling(target.getFileName() + ".new");
                Files.copy(staged, part, StandardCopyOption.REPLACE_EXISTING);
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                last = null;
                break;
            } catch (IOException e) {
                // On Windows the old jar can stay locked for a moment after exit.
                last = e;
                Thread.sleep(250);
            }
        }

        if (last != null) {
            throw last;
        }

        new ProcessBuilder(java(), "-jar", target.toString()).inheritIO().start();
    }

    static String java() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    // ---------------------------------------------------------- the loader

    /** Where the newest downloaded loader is kept, with the release it came from beside it. */
    static Path loaderJar() {
        return LauncherSettings.home().resolve("loader").resolve("octo-loader.jar");
    }

    static String loaderTag() {
        try {
            return Files.readString(loaderJar().resolveSibling("tag.txt"), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return null;
        }
    }

    /** Downloads the release's loader, if it is not the one already downloaded. */
    public static Path downloadLoader(Release release) throws IOException, InterruptedException {
        if (release.loaderUrl() == null) {
            throw new IOException("the release " + release.tag() + " has no loader jar");
        }

        if (release.tag().equals(loaderTag()) && Files.isRegularFile(loaderJar())) {
            return loaderJar();
        }

        Path jar = download(release.loaderUrl(), release.loaderName(), release.checksums(), loaderJar());
        Files.writeString(jar.resolveSibling("tag.txt"), release.tag(), StandardCharsets.UTF_8);
        return jar;
    }

    /** The loader to install: the newest downloaded one, or the one inside this app. */
    static Path loaderToInstall() {
        return Files.isRegularFile(loaderJar()) ? loaderJar() : null;
    }

    /**
     * Installs the newest loader into every Octo installation in the folder.
     *
     * @return the Minecraft versions updated
     */
    public static List<String> updateInstallations(Path gameDirectory, ClientInstaller.Log log) throws IOException {
        List<String> updated = new ArrayList<>();

        for (String version : Versions.octoInstallations(gameDirectory)) {
            installer(gameDirectory, log).install(version);
            updated.add(version);
        }

        return updated;
    }

    static ClientInstaller installer(Path gameDirectory, ClientInstaller.Log log) {
        ClientInstaller installer = new ClientInstaller(gameDirectory, Installer.VERSION, log).requireVanilla(false);
        Path loader = loaderToInstall();
        return loader == null ? installer : installer.loaderJar(loader);
    }

    static Map<String, String> readChecksums(Path file) throws IOException {
        return parseChecksums(Files.readString(file, StandardCharsets.UTF_8));
    }
}
