package org.quiltmc.loader.api;

import java.nio.file.Path;

/** What a Quilt entrypoint is handed: the mod it belongs to. */
public interface ModContainer {
    ModMetadata metadata();

    /** The mod's own jar, as a path. */
    Path rootPath();

    default Path getPath(String file) {
        return rootPath().resolve(file);
    }
}
