package com.mumfrey.liteloader;

import java.io.File;

/**
 * LiteLoader's mod interface, 1.5.2 through 1.12.2.
 *
 * <p>Octo implements it so litemods keep working: they were small, client-side
 * and numerous, and nothing else still loads them.
 */
public interface LiteMod {
    String getName();

    String getVersion();

    void init(File configPath);

    default void upgradeSettings(String version, File configPath, File oldConfigPath) {
    }
}
