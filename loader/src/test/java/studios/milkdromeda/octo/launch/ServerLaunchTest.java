package studios.milkdromeda.octo.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.testkit.JarBuilder;

/**
 * Starting a dedicated server, which is not the same thing as starting a client.
 *
 * <p>Every one of these stopped a real 26.3 server under Octo: the server jar is
 * a bundler with the server inside it, a script-started server has no
 * {@code --gameDir}, and Minecraft refuses an option it does not know.
 */
class ServerLaunchTest {
    @TempDir
    Path temporary;

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Path bundler(byte[] serverJar, byte[] library) throws Exception {
        return new JarBuilder()
                .file("net/minecraft/bundler/Main.class", new byte[] { (byte) 0xCA, (byte) 0xFE })
                .file("version.json", "{\"id\":\"26.3\",\"name\":\"26.3\"}")
                .file("META-INF/main-class", "net.minecraft.server.Main\n")
                .file("META-INF/versions.list", sha256(serverJar) + "\t26.3\t26.3/server-26.3.jar\n")
                .file("META-INF/libraries.list", sha256(library) + "\tcom.example:lib:1.0\tcom/example/lib/1.0/lib-1.0.jar\n")
                .file("META-INF/versions/26.3/server-26.3.jar", serverJar)
                .file("META-INF/libraries/com/example/lib/1.0/lib-1.0.jar", library)
                .writeTo(temporary.resolve("server.jar"));
    }

    @Test
    @DisplayName("the server bundler is opened, and the real server and its libraries are what get loaded")
    void unpacksTheBundler() throws Exception {
        byte[] server = "the real server".getBytes(StandardCharsets.UTF_8);
        byte[] library = "a library".getBytes(StandardCharsets.UTF_8);
        Path jar = bundler(server, library);

        assertTrue(ServerBundler.isBundler(jar));

        ServerBundler.Unpacked unpacked = ServerBundler.unpack(jar, temporary.resolve("game")).orElseThrow();

        assertEquals("net.minecraft.server.Main", unpacked.mainClass());
        assertEquals("26.3", unpacked.version());
        assertEquals(1, unpacked.gameJars().size());
        assertTrue(unpacked.gameJars().get(0).endsWith(Path.of("versions", "26.3", "server-26.3.jar")));
        assertEquals("the real server", Files.readString(unpacked.gameJars().get(0)));
        assertEquals("a library", Files.readString(unpacked.libraries().get(0)));
    }

    @Test
    @DisplayName("a second launch reuses what the first one unpacked")
    void reusesWhatWasUnpacked() throws Exception {
        Path jar = bundler("server".getBytes(StandardCharsets.UTF_8), "lib".getBytes(StandardCharsets.UTF_8));
        Path game = temporary.resolve("game");
        Path first = ServerBundler.unpack(jar, game).orElseThrow().gameJars().get(0);
        long stamp = Files.getLastModifiedTime(first).toMillis();

        Thread.sleep(20);
        ServerBundler.unpack(jar, game);

        assertEquals(stamp, Files.getLastModifiedTime(first).toMillis(), "an intact file should not be rewritten");
    }

    @Test
    @DisplayName("an ordinary jar is not mistaken for a bundler")
    void ordinaryJarIsNotABundler() throws Exception {
        Path jar = new JarBuilder().file("version.json", "{}").writeTo(temporary.resolve("client.jar"));
        assertFalse(ServerBundler.isBundler(jar));
    }

    @Test
    @DisplayName("a server started from a script uses its own directory, not a client's .minecraft")
    void serverUsesItsOwnDirectory() {
        Path chosen = OctoMain.gameDirectory(List.of("--side", "server", "nogui"), "", "C:\\Users\\x\\AppData",
                "/home/x", Side.SERVER);

        assertEquals(Path.of("").toAbsolutePath().normalize(), chosen);
    }

    @Test
    @DisplayName("Octo's own options never reach Minecraft, which refuses options it does not know")
    void stripsOctoOptions() {
        List<String> passed = OctoMain.gameArguments(List.of("--side", "server", "--modsDir", "extra", "nogui",
                "--mcVersion=26.3", "--port", "25566"));

        assertEquals(List.of("nogui", "--port", "25566"), passed);
    }

    @Test
    @DisplayName("an option the launcher passes twice reaches the game once")
    void dropsRepeatedOptions() {
        // The vanilla profile passes --gameDir and the Octo profile inherits it
        // and passes it again; jopt-simple throws on the second.
        List<String> passed = OctoMain.gameArguments(List.of("--username", "Steve", "--gameDir", "/game",
                "--version", "26.3", "--gameDir", "/game", "--demo", "--demo"));

        assertEquals(List.of("--username", "Steve", "--gameDir", "/game", "--version", "26.3", "--demo"), passed);
    }
}
