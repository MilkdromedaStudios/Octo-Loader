package studios.milkdromeda.octo.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import studios.milkdromeda.octo.testkit.ByteClassLoader;
import studios.milkdromeda.octo.testkit.SourceCompiler;

/**
 * Reading the game's registries back after its bootstrap.
 *
 * <p>The fixtures below are the shape Minecraft's registries have, not the
 * classes themselves: a registry of registries, iterable, answering
 * {@code keySet()} and {@code getKey(Object)}, with a defaulted variant that
 * knows the key of the entry it falls back to. That is the whole surface the
 * probe touches, and it is the part that has been stable across every version
 * that has these classes at all.
 */
class RegistryProbeTest {
    /** The shape, reduced to what the probe reads. */
    private static final Map<String, String> REGISTRY_CLASSES = Map.of(
            "net.minecraft.core.Registry", """
                    package net.minecraft.core;

                    import java.util.LinkedHashMap;
                    import java.util.Map;
                    import java.util.Set;

                    public class Registry<T> implements Iterable<T> {
                        public final Map<String, T> entries = new LinkedHashMap<>();
                        public String name = "";

                        public Set<String> keySet() {
                            return entries.keySet();
                        }

                        public String getKey(Object value) {
                            for (Map.Entry<String, T> entry : entries.entrySet()) {
                                if (entry.getValue() == value) {
                                    return entry.getKey();
                                }
                            }

                            return null;
                        }

                        public java.util.Iterator<T> iterator() {
                            return entries.values().iterator();
                        }
                    }
                    """,
            "net.minecraft.core.DefaultedRegistry", """
                    package net.minecraft.core;

                    public class DefaultedRegistry<T> extends Registry<T> {
                        public String defaultKey = "minecraft:empty";

                        public String getDefaultKey() {
                            return defaultKey;
                        }
                    }
                    """);

    @Test
    @DisplayName("registries that were filled are reported as fine")
    void healthyRegistries() throws Exception {
        ClassLoader game = game("""
                package net.minecraft.core.registries;

                import net.minecraft.core.DefaultedRegistry;
                import net.minecraft.core.Registry;

                public final class BuiltInRegistries {
                    public static final Registry<Registry<?>> REGISTRY = new Registry<>();

                    static {
                        Registry<Object> blocks = new DefaultedRegistry<>();
                        blocks.entries.put("minecraft:empty", new Object());
                        blocks.entries.put("minecraft:stone", new Object());
                        REGISTRY.entries.put("minecraft:block", blocks);
                    }
                }
                """);

        RegistryProbe.Findings findings = RegistryProbe.inspect(game);

        assertTrue(findings.isEmpty(), () -> "nothing should be wrong: " + findings.describe());
    }

    @Test
    @DisplayName("an empty registry and a missing default are both named")
    void theFailureBehindTheNullDefault() {
        ClassLoader game = game("""
                package net.minecraft.core.registries;

                import net.minecraft.core.DefaultedRegistry;
                import net.minecraft.core.Registry;

                public final class BuiltInRegistries {
                    public static final Registry<Registry<?>> REGISTRY = new Registry<>();

                    static {
                        // What the player's log showed: filled registries beside
                        // two that never got anything.
                        Registry<Object> items = new Registry<>();
                        items.entries.put("minecraft:stick", new Object());
                        REGISTRY.entries.put("minecraft:item", items);

                        REGISTRY.entries.put("minecraft:custom_stat", new Registry<Object>());

                        DefaultedRegistry<Object> chunkStatus = new DefaultedRegistry<>();
                        REGISTRY.entries.put("minecraft:chunk_status", chunkStatus);
                    }
                }
                """);

        RegistryProbe.Findings findings = RegistryProbe.inspect(game);

        assertFalse(findings.isEmpty());
        assertEquals(List.of("minecraft:custom_stat", "minecraft:chunk_status"), findings.empty());
        assertEquals(List.of("minecraft:chunk_status"), findings.missingDefault(),
                "a defaulted registry with no default is the one that produces the null-default crash");

        List<String> described = findings.describe();

        assertEquals(2, described.size(), "each registry is named once: " + described);
        assertTrue(described.get(0).startsWith("minecraft:chunk_status"), described.toString());
        assertTrue(described.get(0).contains("no default entry"), described.toString());
        assertTrue(described.get(1).equals("minecraft:custom_stat is empty"), described.toString());
    }

    @Test
    @DisplayName("a Minecraft without these classes is not a failure")
    void nothingToRead() {
        assertTrue(RegistryProbe.inspect(new ByteClassLoader(getClass().getClassLoader())).isEmpty());
    }

    private static ClassLoader game(String builtInRegistries) {
        java.util.Map<String, String> sources = new java.util.HashMap<>(REGISTRY_CLASSES);
        sources.put("net.minecraft.core.registries.BuiltInRegistries", builtInRegistries);
        return new ByteClassLoader(RegistryProbeTest.class.getClassLoader())
                .defineAll(SourceCompiler.compile(sources, List.of()));
    }
}
