package studios.milkdromeda.octo.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import studios.milkdromeda.octo.launch.LaunchContext;
import studios.milkdromeda.octo.mod.ModCandidate;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.ModMetadata;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * The mods nobody installed: the game, the JVM, and the loader.
 *
 * <p>Every ecosystem lists these alongside real mods, and mods rely on them
 * being there. Fabric API's resource loader reads the game's own language file
 * through {@code getModContainer("minecraft").orElseThrow()}, and with no such
 * container the dedicated server died loading its first translated string.
 * Mod Menu lists them; a dependency on {@code mixinextras} is resolved against
 * the copy the loader ships.
 */
public final class BuiltinMods {
    private static final OctoLog LOG = OctoLog.of(BuiltinMods.class);

    /** The ids Octo itself answers for. A jar in the mods folder cannot replace these. */
    public static final Set<String> IDS = Set.of("minecraft", "java", "fabricloader", "mixinextras", "octoloader");

    /**
     * The Fabric Loader version Octo's Fabric surface corresponds to.
     *
     * <p>Not decoration: every Fabric API module for 26.3 declares
     * {@code fabricloader >=0.19.3} and mods range-check it, so reporting an older
     * number makes each of them a warning and sends a mod that gates a feature on
     * the loader version down the wrong branch.
     */
    public static final String FABRIC_LOADER_VERSION = "0.20.1";

    /** The bundled MixinExtras, which current mods declare a dependency on. */
    public static final String MIXINEXTRAS_VERSION = "0.5.5";

    /** The loader's logo in its own jar, for mod lists to show beside its name. */
    static final String ICON = "assets/octoloader/icon.png";

    private BuiltinMods() {
    }

    public static List<LoadedMod> create(LaunchContext context) {
        List<LoadedMod> out = new ArrayList<>();
        Path ownJar = ownJar();

        List<Path> gameJars = context.gameJars();
        Path gameRoot = gameJars.isEmpty() ? context.gameDir() : gameJars.get(0);
        LoadedMod minecraft = builtin("minecraft", "Minecraft", context.minecraftVersion().isBlank()
                ? "0.0.0" : context.minecraftVersion(), gameRoot, "The game itself.");

        if (gameJars.size() > 1) {
            minecraft.extraRoots(gameJars.subList(1, gameJars.size()));
        }

        out.add(minecraft);
        out.add(builtin("java", "Java " + Runtime.version().feature(),
                String.valueOf(Runtime.version().feature()), Path.of(System.getProperty("java.home", ".")),
                "The Java runtime the game is running on."));
        out.add(builtin("octoloader", "Octo Loader", OctoRuntime.VERSION, ownJar,
                "Runs Fabric, Quilt, Forge and NeoForge mods side by side, including mods made for other "
                        + "Minecraft versions.", ICON));
        out.add(builtin("fabricloader", "Fabric Loader (Octo)", FABRIC_LOADER_VERSION, ownJar,
                "Fabric Loader's API, provided by Octo Loader."));
        out.add(builtin("mixinextras", "MixinExtras", MIXINEXTRAS_VERSION, ownJar,
                "Companion library to Mixin, bundled with the loader."));
        return out;
    }

    private static LoadedMod builtin(String id, String name, String version, Path path, String description) {
        return builtin(id, name, version, path, description, null);
    }

    private static LoadedMod builtin(String id, String name, String version, Path path, String description,
            String icon) {
        ModMetadata metadata = ModMetadata.builder(id, ModFormat.BUILTIN)
                .name(name)
                .version(version)
                .description(description)
                .icon(icon)
                .build();
        return new LoadedMod(new ModCandidate(path, metadata, null));
    }

    private static Path ownJar() {
        try {
            var source = BuiltinMods.class.getProtectionDomain().getCodeSource();
            return source == null ? Path.of(".") : Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (Exception e) {
            LOG.debug("could not locate the loader's own jar: {}", e.toString());
            return Path.of(".");
        }
    }
}
