package studios.milkdromeda.octo.launch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.version.Version;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Fetches the game-side API of NeoForge and Forge, for the mods that link
 * against it.
 *
 * <p>Fabric mods depend on Fabric API, which is a mod like any other and sits in
 * the mods folder. NeoForge and Forge mods depend on the equivalent half of
 * their loader — {@code NeoForge.EVENT_BUS}, {@code ModConfigSpec}, the
 * capability types, every game event — and that half ships inside the loader,
 * not as a mod. Octo implements the loader half itself; this fetches the other
 * half, the real {@code universal} jar for the running game version from the
 * project's own Maven repository, and puts it on the class path behind Octo's
 * own classes. A mod constructing against it then gets the real types, with the
 * real hierarchy and the real config spec, instead of stand-ins.
 *
 * <p>Those jars are written for a game their installer has patched, and this
 * one has not been, so the calls they make into patched-in game methods are
 * covered by the same bypass translated mods get. What they need from the
 * patches mostly fires events; what nothing fires, nothing waits on.
 *
 * <p>Downloaded once per version and kept in {@code .octo/cache/libraries}.
 * {@code -Docto.offline=true} uses only what is cached, and
 * {@code -Docto.noApiLibraries=true} turns this off.
 */
public final class LoaderApiLibraries {
    private static final OctoLog LOG = OctoLog.of(LoaderApiLibraries.class);

    private static final String NEOFORGE = "https://maven.neoforged.net/releases/net/neoforged/neoforge/";
    private static final String FORGE = "https://maven.minecraftforge.net/net/minecraftforge/forge/";

    /** Packages those jars put their game-side API in; the game-gap bypass covers them. */
    public static final List<String> PACKAGES = List.of(
            "net/neoforged/neoforge/",
            "net/minecraftforge/common/", "net/minecraftforge/event/", "net/minecraftforge/client/",
            "net/minecraftforge/registries/", "net/minecraftforge/network/", "net/minecraftforge/items/",
            "net/minecraftforge/fluids/", "net/minecraftforge/energy/", "net/minecraftforge/server/",
            "net/minecraftforge/data/", "net/minecraftforge/entity/", "net/minecraftforge/resource/",
            "net/minecraftforge/internal/", "net/minecraftforge/versions/", "net/minecraftforge/gametest/");

    private LoaderApiLibraries() {
    }

    /** The API jars the mods in this launch need, fetched or found in the cache. */
    public static List<Path> resolve(LaunchContext context, List<LoadedMod> mods) {
        List<Path> out = new ArrayList<>();

        if (Boolean.getBoolean("octo.noApiLibraries") || context.minecraftVersion().isBlank()) {
            return out;
        }

        Path cache = context.octoDir().resolve("cache").resolve("libraries");
        boolean offline = Boolean.getBoolean("octo.offline");
        Version minecraft = Version.parse(context.minecraftVersion());

        if (mods.stream().anyMatch(mod -> mod.format() == ModFormat.NEOFORGE)) {
            neoForge(minecraft, cache, offline).ifPresent(out::add);
        }

        if (mods.stream().anyMatch(mod -> mod.format() == ModFormat.FORGE)) {
            forge(context.minecraftVersion(), cache, offline).ifPresent(out::add);
        }

        return out;
    }

    /** NeoForge numbers itself 26.3.0.x for 26.3, and 21.1.x for 1.21.1. */
    private static Optional<Path> neoForge(Version minecraft, Path cache, boolean offline) {
        String prefix = minecraft.part(0) >= 26
                ? minecraft.part(0) + "." + minecraft.part(1) + "." + minecraft.part(2) + "."
                : minecraft.part(1) + "." + minecraft.part(2) + ".";
        return fetchLatest("neoforge", NEOFORGE, prefix, version -> NEOFORGE + version + "/neoforge-" + version
                + "-universal.jar", cache, offline);
    }

    /** Forge numbers itself 26.3-66.x. */
    private static Optional<Path> forge(String minecraft, Path cache, boolean offline) {
        String prefix = minecraft.replaceAll("[-+ ].*$", "") + "-";
        return fetchLatest("forge", FORGE, prefix, version -> FORGE + version + "/forge-" + version
                + "-universal.jar", cache, offline);
    }

    private static Optional<Path> fetchLatest(String name, String repository, String prefix,
            java.util.function.Function<String, String> jarUrl, Path cache, boolean offline) {
        Optional<Path> cached = newestCached(name, prefix, cache);

        if (offline) {
            return cached;
        }

        Optional<String> latest = latestVersion(repository, prefix);

        if (latest.isEmpty()) {
            if (cached.isEmpty()) {
                LOG.info("no {} release for this Minecraft was found; its mods get stand-ins for its API", name);
            }

            return cached;
        }

        Path target = cache.resolve(name + "-" + latest.get() + "-universal.jar");

        if (Files.isRegularFile(target)) {
            return Optional.of(target);
        }

        try {
            Files.createDirectories(cache);
            Path temporary = target.resolveSibling(target.getFileName() + ".part");
            HttpResponse<Path> response = client().send(request(jarUrl.apply(latest.get())),
                    HttpResponse.BodyHandlers.ofFile(temporary));

            if (response.statusCode() != 200) {
                Files.deleteIfExists(temporary);
                return cached;
            }

            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("downloaded the {} {} API for its mods", name, latest.get());
            return Optional.of(target);
        } catch (IOException e) {
            LOG.warn("could not download the {} API: {}", name, e.toString());
            return cached;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cached;
        }
    }

    private static Optional<String> latestVersion(String repository, String prefix) {
        try {
            HttpResponse<String> response = client().send(request(repository + "maven-metadata.xml"),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                return Optional.empty();
            }

            Matcher matcher = Pattern.compile("<version>([^<]+)</version>").matcher(response.body());
            String best = null;

            while (matcher.find()) {
                String version = matcher.group(1);

                if (version.startsWith(prefix) && (best == null
                        || Version.parse(version).compareTo(Version.parse(best)) > 0)) {
                    best = version;
                }
            }

            return Optional.ofNullable(best);
        } catch (IOException e) {
            LOG.debug("could not read {}: {}", repository, e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static Optional<Path> newestCached(String name, String prefix, Path cache) {
        if (!Files.isDirectory(cache)) {
            return Optional.empty();
        }

        try (Stream<Path> files = Files.list(cache)) {
            return files.filter(file -> file.getFileName().toString().startsWith(name + "-" + prefix))
                    .filter(file -> file.getFileName().toString().endsWith("-universal.jar"))
                    .max(java.util.Comparator.comparing(file -> Version.parse(file.getFileName().toString()
                            .substring(name.length() + 1).replace("-universal.jar", ""))));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", "Octo-Loader").GET().build();
    }

    private static volatile HttpClient http;

    private static HttpClient client() {
        HttpClient current = http;

        if (current == null) {
            synchronized (LoaderApiLibraries.class) {
                if (http == null) {
                    http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                            .connectTimeout(Duration.ofSeconds(20)).build();
                }

                current = http;
            }
        }

        return current;
    }
}
