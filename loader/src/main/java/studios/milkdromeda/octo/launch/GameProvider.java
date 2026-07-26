package studios.milkdromeda.octo.launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import studios.milkdromeda.octo.mod.ModSource;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Works out what game is being launched.
 *
 * <p>The version matters more here than in other loaders: it decides which era
 * the runtime is, and therefore what every old mod has to be translated into.
 * It is read from the jar itself where possible rather than trusted from the
 * command line, because launchers pass all sorts of things.
 */
public final class GameProvider {
    private static final OctoLog LOG = OctoLog.of(GameProvider.class);

    public static final String CLIENT_MAIN = "net.minecraft.client.main.Main";
    public static final String SERVER_MAIN = "net.minecraft.server.Main";

    private final List<Path> gameJars;

    public GameProvider(List<Path> gameJars) {
        this.gameJars = List.copyOf(gameJars);
    }

    /** Reads {@code version.json} out of the game jar, as shipped since 1.14. */
    public Optional<String> detectVersion() {
        for (Path jar : gameJars) {
            try (ModSource source = ModSource.open(jar)) {
                Optional<byte[]> versionJson = source.read("version.json");

                if (versionJson.isEmpty()) {
                    continue;
                }

                JsonElement root = JsonParser.parseString(new String(versionJson.get(), StandardCharsets.UTF_8));

                if (!root.isJsonObject()) {
                    continue;
                }

                JsonObject json = root.getAsJsonObject();

                for (String key : new String[] { "name", "id" }) {
                    JsonElement value = json.get(key);

                    if (value != null && value.isJsonPrimitive()) {
                        LOG.debug("detected Minecraft {} from {}", value.getAsString(), jar.getFileName());
                        return Optional.of(value.getAsString());
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.debug("could not read version from {}: {}", jar, e.toString());
            }
        }

        return Optional.empty();
    }

    /** Finds the class the game starts from, preferring what is actually in the jar. */
    public Optional<String> detectMainClass(Side side) {
        String preferred = side == Side.SERVER ? SERVER_MAIN : CLIENT_MAIN;
        String fallback = side == Side.SERVER ? CLIENT_MAIN : SERVER_MAIN;

        if (contains(preferred)) {
            return Optional.of(preferred);
        }

        if (contains(fallback)) {
            LOG.warn("no {} in the game jar; falling back to {}", preferred, fallback);
            return Optional.of(fallback);
        }

        // Older versions kept the client entrypoint elsewhere.
        for (String candidate : new String[] { "net.minecraft.client.Minecraft", "Start", "net.minecraft.server.MinecraftServer" }) {
            if (contains(candidate)) {
                return Optional.of(candidate);
            }
        }

        return Optional.empty();
    }

    public boolean contains(String binaryName) {
        String entry = binaryName.replace('.', '/') + ".class";

        for (Path jar : gameJars) {
            try (ModSource source = ModSource.open(jar)) {
                if (source.has(entry)) {
                    return true;
                }
            } catch (IOException e) {
                LOG.debug("could not read {}: {}", jar, e.toString());
            }
        }

        return false;
    }

    public List<Path> gameJars() {
        return gameJars;
    }
}
