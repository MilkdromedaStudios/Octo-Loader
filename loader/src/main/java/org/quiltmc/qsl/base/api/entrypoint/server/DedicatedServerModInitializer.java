package org.quiltmc.qsl.base.api.entrypoint.server;

import org.quiltmc.loader.api.ModContainer;

/** Quilt's dedicated-server initialisation entrypoint. */
public interface DedicatedServerModInitializer {
    void onInitializeServer(ModContainer mod);
}
