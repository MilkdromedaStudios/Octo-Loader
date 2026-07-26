package org.quiltmc.qsl.base.api.entrypoint.client;

import org.quiltmc.loader.api.ModContainer;

/** Quilt's client-side initialisation entrypoint. */
public interface ClientModInitializer {
    void onInitializeClient(ModContainer mod);
}
