package studios.milkdromeda.octo.installer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Installs Octo into a server directory.
 *
 * <p>Servers have no profile system, so what gets written is the loader jar and
 * two start scripts that put it in front of the vanilla server jar. Nothing else
 * in the directory is touched.
 */
public final class ServerInstaller {
    private final Path serverDirectory;
    private final String loaderVersion;
    private final ClientInstaller.Log log;

    public ServerInstaller(Path serverDirectory, String loaderVersion, ClientInstaller.Log log) {
        this.serverDirectory = serverDirectory;
        this.loaderVersion = loaderVersion;
        this.log = log;
    }

    /**
     * @param serverJarName the vanilla server jar in this directory
     */
    public void install(String serverJarName) throws IOException {
        Files.createDirectories(serverDirectory);
        Path library = serverDirectory.resolve("libraries").resolve("octo-loader-" + loaderVersion + ".jar");
        EmbeddedLoader.extractTo(library);
        log.info("installed loader to " + serverDirectory.relativize(library));

        Path serverJar = serverDirectory.resolve(serverJarName);

        if (!Files.isRegularFile(serverJar)) {
            log.info("note: " + serverJarName + " is not here yet - put the vanilla server jar in this"
                    + " directory before starting");
        }

        String classPath = "libraries/octo-loader-" + loaderVersion + ".jar" + java.io.File.pathSeparator + serverJarName;
        String mainClass = "studios.milkdromeda.octo.launch.OctoMain";

        Path shell = serverDirectory.resolve("start-octo-server.sh");
        Files.writeString(shell, """
                #!/bin/sh
                # Starts the Minecraft server with Octo Loader in front of it.
                # Mods go in ./mods — Fabric, Quilt, Forge, NeoForge and older, all at once.
                exec java -Xmx2G -cp "%s" %s --side server "$@"
                """.formatted(classPath, mainClass), StandardCharsets.UTF_8);
        makeExecutable(shell);

        Path batch = serverDirectory.resolve("start-octo-server.bat");
        Files.writeString(batch, """
                @echo off
                rem Starts the Minecraft server with Octo Loader in front of it.
                rem Mods go in .\\mods — Fabric, Quilt, Forge, NeoForge and older, all at once.
                java -Xmx2G -cp "%s" %s --side server %%*
                """.formatted(classPath.replace('/', '\\'), mainClass), StandardCharsets.UTF_8);

        Files.createDirectories(serverDirectory.resolve("mods"));
        log.info("wrote start-octo-server.sh and start-octo-server.bat");
    }

    private static void makeExecutable(Path script) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(script);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(script, permissions);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows has no POSIX permissions, and the .bat is what matters there.
        }
    }
}
