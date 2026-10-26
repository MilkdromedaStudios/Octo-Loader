package studios.milkdromeda.octo.bridge.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.electronwill.nightconfig.core.CommentedConfig;

import net.neoforged.fml.config.IConfigSpec;

/** A mod's config spec is handed its file, as FML would hand it, so it answers. */
class ConfigFilesTest {
    /** NeoForge's way: a loaded config, which the spec corrects and saves. */
    public static final class NeoSpec implements IConfigSpec {
        ILoadedConfig loaded;

        public void acceptConfig(ILoadedConfig config) {
            loaded = config;

            if (!config.config().contains("speed")) {
                config.config().set("speed", 4);
                config.save();
            }
        }
    }

    /** Forge's way: the config itself. */
    public static final class ForgeSpec {
        CommentedConfig config;

        public void acceptConfig(CommentedConfig config) {
            this.config = config;
        }
    }

    @Test
    @DisplayName("a NeoForge spec gets its file, and what it saves is there the next time")
    void neoForgeSpecLoadsAndSaves(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config").resolve("create-server.toml");
        NeoSpec spec = new NeoSpec();
        ConfigFiles.load(spec, () -> file);

        assertNotNull(spec.loaded, "the spec should have been handed a config");
        assertEquals(4, (int) spec.loaded.config().<Integer>get("speed"));
        assertTrue(Files.readString(file).contains("speed"), "the defaults should have been written out");

        NeoSpec again = new NeoSpec();
        ConfigFiles.load(again, () -> file);
        assertEquals(4, (int) again.loaded.config().<Integer>get("speed"), "read back from the file");
    }

    @Test
    @DisplayName("a Forge spec gets its file as a config")
    void forgeSpecLoads(@TempDir Path dir) {
        ForgeSpec spec = new ForgeSpec();
        ConfigFiles.load(spec, () -> dir.resolve("mod-common.toml"));

        assertNotNull(spec.config);
    }

    @Test
    @DisplayName("before Octo has started, a spec still gets a config, held in memory")
    void noConfigDirectory() {
        NeoSpec spec = new NeoSpec();
        ConfigFiles.load(spec, () -> {
            throw new IllegalStateException("Octo Loader accessed before it started");
        });

        assertEquals(4, (int) spec.loaded.config().<Integer>get("speed"));
    }
}
