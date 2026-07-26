package org.quiltmc.qsl.base.api.entrypoint;

import org.quiltmc.loader.api.ModContainer;

/** Quilt's initialisation entrypoint, run on both sides. */
public interface ModInitializer {
    void onInitialize(ModContainer mod);
}
