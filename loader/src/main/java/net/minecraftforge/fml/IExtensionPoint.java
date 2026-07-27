package net.minecraftforge.fml;

import java.util.function.Supplier;

/**
 * Forge's extension point.
 *
 * <p>Unlike NeoForge's marker interface, Forge's carries the value, and mods
 * register the well-known {@code DisplayTest} through it.
 */
public interface IExtensionPoint<T extends Record> {
    /** The server-list compatibility check a mod declares. */
    record DisplayTest(Supplier<String> suppliedVersion,
                       java.util.function.BiPredicate<String, Boolean> remoteVersionTest) {
    }
}
