package studios.milkdromeda.octo.launcher.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

class GameTest {
    private static final Rules.Platform LINUX = new Rules.Platform("linux", "amd64", "6.1");
    private static final Rules.Platform WINDOWS_11 = new Rules.Platform("windows", "amd64", "10.0.22631");
    private static final Rules.Platform MAC_ARM = new Rules.Platform("osx", "aarch64", "14.2");

    @TempDir
    Path game;

    private static com.google.gson.JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    @Test
    void rulesAllowOnlyWhatARuleAllowsAndTheLastMatchDecides() {
        assertTrue(Rules.allows(null, LINUX, Map.of()), "no rules: always");
        assertTrue(Rules.allows(json("[{\"action\":\"allow\",\"os\":{\"name\":\"osx\"}}]"), MAC_ARM, Map.of()));
        assertFalse(Rules.allows(json("[{\"action\":\"allow\",\"os\":{\"name\":\"osx\"}}]"), LINUX, Map.of()));
        // Everything but macOS, the way old LWJGL was listed.
        String notMac = "[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"osx\"}}]";
        assertTrue(Rules.allows(json(notMac), LINUX, Map.of()));
        assertFalse(Rules.allows(json(notMac), MAC_ARM, Map.of()));
        assertFalse(Rules.allows(json("[{\"action\":\"allow\",\"os\":{\"arch\":\"x86\"}}]"), LINUX, Map.of()),
                "x86 means 32-bit only");
        assertFalse(Rules.allows(json("[{\"action\":\"allow\",\"features\":{\"is_demo_user\":true}}]"), LINUX,
                Map.of()));
        assertTrue(Rules.allows(json("[{\"action\":\"allow\",\"features\":{\"has_custom_resolution\":true}}]"),
                LINUX, Map.of("has_custom_resolution", true)));
    }

    @Test
    void windowsVersionRangesUseTheBuildNumber() {
        String zgc = "[{\"action\":\"allow\",\"os\":{\"name\":\"windows\",\"versionRange\":{\"min\":\"10.0.17134\"}}}]";
        String g1 = "[{\"action\":\"allow\",\"os\":{\"name\":\"windows\",\"versionRange\":{\"max\":\"10.0.17134\"}}}]";
        assertTrue(Rules.allows(json(zgc), WINDOWS_11, Map.of()));
        assertFalse(Rules.allows(json(g1), WINDOWS_11, Map.of()));
        Rules.Platform old = new Rules.Platform("windows", "amd64", "10.0.16299");
        assertFalse(Rules.allows(json(zgc), old, Map.of()));
        assertTrue(Rules.allows(json(g1), old, Map.of()));
    }

    @Test
    void libraryNamesBecomeMavenPaths() {
        assertEquals("org/lwjgl/lwjgl/3.4.3/lwjgl-3.4.3-natives-linux.jar",
                Libraries.path("org.lwjgl:lwjgl:3.4.3:natives-linux"));
        assertEquals("com/example/thing/1.0/thing-1.0.zip", Libraries.path("com.example:thing:1.0@zip"));
    }

    private void version(String id, String body) throws IOException {
        Path file = VersionProfile.jsonFile(game, id);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }

    /** A vanilla version and Octo's on top of it, as the installer writes it. */
    private void installed() throws IOException {
        version("26.3", """
                {"id":"26.3","type":"release","mainClass":"net.minecraft.client.main.Main","assets":"34",
                 "assetIndex":{"id":"34","sha1":"a","size":1,"url":"https://x/34.json"},
                 "javaVersion":{"component":"java-runtime-epsilon","majorVersion":25},
                 "downloads":{"client":{"sha1":"c","size":10,"url":"https://x/client.jar"}},
                 "arguments":{
                   "game":["--username","${auth_player_name}","--version","${version_name}",
                           "--gameDir","${game_directory}","--uuid","${auth_uuid}",
                           "--accessToken","${auth_access_token}","--xuid","${auth_xuid}",
                           {"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"}],
                   "jvm":[{"rules":[{"action":"allow","os":{"name":"osx"}}],"value":["-XstartOnFirstThread"]},
                          "-Djava.library.path=${natives_directory}","-cp","${classpath}"],
                   "default-user-jvm":["-Xms2G","-Xmx4G","-XX:+UseCompactObjectHeaders"]},
                 "libraries":[
                   {"name":"org.ow2.asm:asm:9.6","downloads":{"artifact":{"path":"org/ow2/asm/asm/9.6/asm-9.6.jar",
                     "sha1":"s","size":1,"url":"https://libraries.minecraft.net/org/ow2/asm/asm/9.6/asm-9.6.jar"}}},
                   {"name":"ca.weblite:java-objc-bridge:1.1","rules":[{"action":"allow","os":{"name":"osx"}}],
                    "downloads":{"artifact":{"path":"ca/weblite/java-objc-bridge/1.1/java-objc-bridge-1.1.jar",
                     "sha1":"s","size":1,"url":"https://x"}}}]}""");
        version("octo-loader-26.3", """
                {"id":"octo-loader-26.3","inheritsFrom":"26.3","type":"release",
                 "mainClass":"studios.milkdromeda.octo.launch.OctoMain",
                 "arguments":{"game":["--gameDir","${game_directory}"],"jvm":[]},
                 "libraries":[{"name":"studios.milkdromeda:octo-loader:1.0.0"},
                              {"name":"org.ow2.asm:asm:9.8","url":"https://maven.example/"}]}""");
    }

    @Test
    void anInheritingVersionFoldsInItsParent() throws IOException {
        installed();
        VersionProfile profile = VersionProfile.load(game, "octo-loader-26.3");

        assertEquals("studios.milkdromeda.octo.launch.OctoMain", profile.mainClass, "the child's main class wins");
        assertEquals("26.3", profile.jarVersion, "the game's jar is the parent's");
        assertEquals("34", profile.assets);
        assertEquals("java-runtime-epsilon", profile.javaComponent(true));
        assertEquals(25, profile.javaMajor());

        List<Libraries.Artifact> classpath = Libraries.classpath(profile, game.resolve("libraries"), LINUX);
        assertEquals(List.of("studios.milkdromeda:octo-loader:1.0.0", "org.ow2.asm:asm:9.8"),
                classpath.stream().map(Libraries.Artifact::name).toList(),
                "the child's libraries first; its newer ASM replaces the game's, and the Mac-only one is left out");
        assertFalse(classpath.get(0).downloadable(), "Octo's own loader is put in place by its installer");
        assertEquals("https://maven.example/org/ow2/asm/asm/9.8/asm-9.8.jar", classpath.get(1).url());
    }

    @Test
    void theCommandStartsTheGameAsTheVersionDescribesIt() throws IOException {
        installed();
        VersionProfile profile = VersionProfile.load(game, "octo-loader-26.3");
        GameLaunch.Options options = new GameLaunch.Options(Path.of("/java/bin/java"), 6144, List.of("-Dextra=1"),
                0, 0, "octo-launcher", "1.0.0");
        GameLaunch.Player player = new GameLaunch.Player("Steve", "0123456789abcdef0123456789abcdef", "secret-token",
                null);
        List<String> command = GameLaunch.command(game, profile, LINUX, player, options, game.resolve("natives"));

        assertEquals("/java/bin/java", command.get(0));
        assertTrue(command.contains("-Xmx6144M"), "the player's memory");
        assertFalse(command.contains("-Xmx4G"), "in place of the game's suggestion");
        assertTrue(command.contains("-XX:+UseCompactObjectHeaders"), "the game's other suggestions stay");
        assertTrue(command.contains("-Dextra=1"));
        assertFalse(command.contains("-XstartOnFirstThread"), "a macOS option, not on Linux");
        int main = command.indexOf("studios.milkdromeda.octo.launch.OctoMain");
        assertTrue(main > command.indexOf("-cp"), "the main class after Java's options");
        String classpath = command.get(command.indexOf("-cp") + 1);
        assertTrue(classpath.endsWith(game.resolve("versions/26.3/26.3.jar").toAbsolutePath().toString()),
                "the game's jar last on the class path");

        List<String> arguments = command.subList(main + 1, command.size());
        assertEquals(1, arguments.stream().filter("--gameDir"::equals).count(), "--gameDir said once");
        assertEquals("Steve", arguments.get(arguments.indexOf("--username") + 1));
        assertEquals("secret-token", arguments.get(arguments.indexOf("--accessToken") + 1));
        assertFalse(arguments.contains("--xuid"), "an option with no value is left out");
        assertFalse(arguments.contains("--demo"));
        assertFalse(GameLaunch.printable(command, "secret-token").contains("secret-token"),
                "the token never reaches the log");
    }

    @Test
    void versionsBefore113GetTheirArgumentsFromOneString() throws IOException {
        version("1.12.2", """
                {"id":"1.12.2","mainClass":"net.minecraft.client.main.Main","assets":"1.12",
                 "minecraftArguments":"--username ${auth_player_name} --session ${auth_session} --userType ${user_type}",
                 "downloads":{"client":{"sha1":"c","size":10,"url":"https://x/client.jar"}},"libraries":[]}""");
        VersionProfile profile = VersionProfile.load(game, "1.12.2");
        List<String> command = GameLaunch.command(game, profile, LINUX, new GameLaunch.Player("Alex", "u", "t", null),
                new GameLaunch.Options(Path.of("java"), 2048, List.of(), 0, 0, "octo", "1"), game.resolve("n"));

        assertTrue(command.contains("-cp"), "Java's options are supplied for it");
        assertEquals("token:t:u", command.get(command.indexOf("--session") + 1));
        assertEquals("msa", command.get(command.indexOf("--userType") + 1));
        assertEquals("jre-legacy", profile.javaComponent(false));
        assertEquals("java-runtime-gamma", profile.javaComponent(true), "with Octo, Java 17 at least");
        assertTrue(profile.supportsOcto());
    }

    @Test
    void versionsOnLwjgl2CannotHostOcto() throws IOException {
        version("1.8.9", """
                {"id":"1.8.9","mainClass":"net.minecraft.client.main.Main","minecraftArguments":"",
                 "libraries":[{"name":"org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209"}]}""");
        assertFalse(VersionProfile.load(game, "1.8.9").supportsOcto(), "LWJGL 2 loads its natives on Java 8 only");
    }

    @Test
    void aDownloadMustMatchItsChecksum() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        server.createContext("/file", exchange -> {
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            Path good = game.resolve("good.txt");
            AtomicLong counted = new AtomicLong();
            Net.download(url, good, "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", counted);
            assertEquals("hello", Files.readString(good));
            assertEquals(5, counted.get());

            Path bad = game.resolve("bad.txt");
            AtomicLong none = new AtomicLong();
            assertThrows(IOException.class, () -> Net.download(url, bad, "0000000000000000000000000000000000000000",
                    none));
            assertFalse(Files.exists(bad), "a file that does not match is not kept");
            assertFalse(Files.exists(game.resolve("bad.txt.part")));
            assertEquals(0, none.get(), "nor counted as progress");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deletingRemovesOnlyTheKindsChosenAndNeverFollowsALink() throws IOException {
        Files.createDirectories(game.resolve("versions/26.3"));
        Files.writeString(game.resolve("versions/26.3/26.3.jar"), "jar");
        Files.createDirectories(game.resolve("saves/World"));
        Files.writeString(game.resolve("saves/World/level.dat"), "world");
        Files.createDirectories(game.resolve("mods"));
        Path outside = Files.createTempDirectory("octo-outside");
        Files.writeString(outside.resolve("precious.txt"), "keep me");

        try {
            Files.createSymbolicLink(game.resolve("assets"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            // No links here; the rest still holds.
        }

        Path runtimes = game.resolve("runtimes-elsewhere");
        Files.createDirectories(runtimes.resolve("java-runtime-epsilon"));
        Files.writeString(runtimes.resolve("java-runtime-epsilon/java"), "java");

        Map<GameFolder.Kind, Long> sizes = GameFolder.sizes(game, runtimes);
        assertTrue(sizes.get(GameFolder.Kind.GAME_FILES) >= 7);
        assertEquals(5, sizes.get(GameFolder.Kind.WORLDS));

        GameFolder.delete(game, Set.of(GameFolder.Kind.GAME_FILES), runtimes);

        assertFalse(Files.exists(game.resolve("versions")));
        assertFalse(Files.exists(runtimes));
        assertTrue(Files.exists(game.resolve("saves/World/level.dat")), "worlds were not chosen");
        assertTrue(Files.exists(game.resolve("mods")));
        assertTrue(Files.exists(outside.resolve("precious.txt")), "a link is removed, not what it points to");
        assertTrue(Files.exists(game), "the Minecraft folder itself stays");
    }

    @Test
    void deletingIsRefusedOutsideAMinecraftFolder(@TempDir Path elsewhere) throws IOException {
        Files.createDirectories(elsewhere.resolve("assets"));
        Files.writeString(elsewhere.resolve("assets/thesis.txt"), "years of work");

        assertThrows(IOException.class, () -> GameFolder.delete(elsewhere, Set.of(GameFolder.Kind.GAME_FILES),
                null));
        assertTrue(Files.exists(elsewhere.resolve("assets/thesis.txt")));
    }

    @Test
    void theRuntimeForEachSystem() {
        assertEquals("windows-x64", JavaRuntimes.platformKey(WINDOWS_11));
        assertEquals("mac-os-arm64", JavaRuntimes.platformKey(MAC_ARM));
        assertEquals("linux", JavaRuntimes.platformKey(LINUX));
        assertTrue(JavaRuntimes.executable(Path.of("rt"), WINDOWS_11).toString().endsWith("javaw.exe"),
                "no console window behind the game");
    }
}
