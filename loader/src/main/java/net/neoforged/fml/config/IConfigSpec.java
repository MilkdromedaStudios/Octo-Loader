package net.neoforged.fml.config;

/**
 * The shape of a mod's configuration file.
 *
 * <p>Octo does not run NeoForge's configuration system — it has no TOML
 * round-tripping, no file watcher and no reload events — but the interface has
 * to exist, because a mod's own config class implements it and every class that
 * mentions that class fails to link without it. That is the whole reason
 * {@code ponder} could not be constructed: not one line of its configuration
 * code ran, it simply could not be loaded.
 */
public interface IConfigSpec {
    /** Whether {@code config} is a config this spec describes. */
    default boolean isCorrect(Object config) {
        return true;
    }

    /** Fills in anything missing from {@code config}; a no-op with no config system. */
    default void correct(Object config) {
    }

    default void acknowledgeCorrection(Object config, Object action, Object incorrectValue) {
    }

    /** Called when the config file this spec describes has been read. */
    default void afterReload() {
    }

    default boolean isEmpty() {
        return false;
    }
}
