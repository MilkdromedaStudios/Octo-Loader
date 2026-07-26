package studios.milkdromeda.octo.mod.format;

import java.util.List;

import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.ModMetadata;
import studios.milkdromeda.octo.mod.ModSource;

/** Reads one ecosystem's metadata file and produces Octo's canonical form. */
public interface MetadataParser {
    ModFormat format();

    /** {@code true} when this source carries the file this parser understands. */
    default boolean matches(ModSource source) {
        String path = format().metadataPath();
        return path != null && source.has(path);
    }

    /**
     * A single file can declare several mods — Forge's {@code [[mods]]} and the
     * old {@code mcmod.info} both allow it.
     *
     * @throws MetadataException when the file is present but unusable
     */
    List<ModMetadata> parse(ModSource source) throws MetadataException;

    /** Standard entrypoint phase names, shared by every bridge. */
    final class Phases {
        /** Before the game class loads. */
        public static final String PRELAUNCH = "preLaunch";
        /** Both sides, at construction. */
        public static final String MAIN = "main";
        public static final String CLIENT = "client";
        public static final String SERVER = "server";
        /** Forge/NeoForge {@code @Mod} classes: constructed, then fed lifecycle events. */
        public static final String MOD_CLASS = "modClass";
        /** LiteLoader's {@code LiteMod*} classes. */
        public static final String LITEMOD = "liteMod";

        private Phases() {
        }
    }
}
