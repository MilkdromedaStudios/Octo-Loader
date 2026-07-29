package studios.milkdromeda.octo.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import studios.milkdromeda.octo.testkit.ByteClassLoader;
import studios.milkdromeda.octo.testkit.SourceCompiler;

/**
 * The game's own start-up prologue, and what happens when it does not finish.
 *
 * <p>The failure being guarded against here reaches the player as
 * {@code Cannot invoke "net.minecraft.core.Holder$Reference.value()" because
 * "this.defaultValue" is null} during "Initializing game" — a crash with nothing
 * in it to say that a bootstrap half an hour earlier in the launch is the
 * reason.
 */
class GameBootstrapTest {
    /** A stand-in for Minecraft's own: the flag goes up before the work is done. */
    private static final String BOOTSTRAP = """
            package net.minecraft.server;

            public class Bootstrap {
                public static volatile boolean isBootstrapped;
                public static int calls;
                public static boolean failFirstCall = true;

                public static void bootStrap() {
                    if (isBootstrapped) {
                        return;
                    }

                    isBootstrapped = true;
                    calls++;

                    if (failFirstCall && calls == 1) {
                        throw new IllegalStateException("a mod threw during registry setup");
                    }
                }
            }
            """;

    @Test
    @DisplayName("a bootstrap that throws leaves the game able to try again")
    void aFailedBootstrapIsRetryable() throws Exception {
        ClassLoader game = load(Map.of("net.minecraft.server.Bootstrap", BOOTSTRAP));

        GameBootstrap.Outcome outcome = GameBootstrap.run(game);

        assertFalse(outcome.bootstrapped(), "the registries were not built");
        assertNotNull(outcome.failure(), "and the launch should know why");
        assertTrue(outcome.retried(), "the game must be left able to run its own bootstrap");

        Class<?> bootstrap = Class.forName("net.minecraft.server.Bootstrap", true, game);
        assertFalse(bootstrap.getField("isBootstrapped").getBoolean(null),
                "the flag Minecraft checks must be back down, or it skips the retry and the registries "
                        + "stay half built");

        // Which is the whole point: the game's own call now does the work.
        bootstrap.getMethod("bootStrap").invoke(null);
        assertEquals(2, bootstrap.getField("calls").getInt(null));
        assertTrue(bootstrap.getField("isBootstrapped").getBoolean(null));
    }

    @Test
    @DisplayName("a bootstrap that works is reported as done, once")
    void aWorkingBootstrapIsLeftAlone() throws Exception {
        ClassLoader game = load(Map.of("net.minecraft.server.Bootstrap",
                BOOTSTRAP.replace("failFirstCall = true", "failFirstCall = false")));

        GameBootstrap.Outcome outcome = GameBootstrap.run(game);

        assertTrue(outcome.bootstrapped());
        assertNull(outcome.failure());
        assertFalse(outcome.retried(), "nothing failed, so nothing needs retrying");

        Class<?> bootstrap = Class.forName("net.minecraft.server.Bootstrap", true, game);
        assertTrue(bootstrap.getField("isBootstrapped").getBoolean(null));
        assertEquals(1, bootstrap.getField("calls").getInt(null));
    }

    @Test
    @DisplayName("registries that did not survive the bootstrap are caught here, not two minutes later")
    void anEmptyRegistryIsAFailedBootstrap() {
        ClassLoader game = load(Map.of(
                "net.minecraft.server.Bootstrap", BOOTSTRAP.replace("failFirstCall = true",
                        "failFirstCall = false"),
                "net.minecraft.world.level.block.Blocks", """
                        package net.minecraft.world.level.block;

                        public class Blocks {
                            public static final Object AIR;

                            static {
                                if (Boolean.TRUE) {
                                    throw new IllegalStateException("Not bootstrapped (called from registry "
                                            + "minecraft:block)");
                                }

                                AIR = new Object();
                            }
                        }
                        """));

        GameBootstrap.Outcome outcome = GameBootstrap.run(game);

        assertFalse(outcome.bootstrapped(), "the bootstrap returned, but it built nothing");
        assertNotNull(outcome.failure());
        assertTrue(outcome.failure().toString().contains("Not bootstrapped"),
                "the real cause should survive: " + outcome.failure());
    }

    @Test
    @DisplayName("-Docto.noEarlyBootstrap leaves the game's start-up entirely to the game")
    void theEscapeHatch() throws Exception {
        ClassLoader game = load(Map.of("net.minecraft.server.Bootstrap", BOOTSTRAP));
        System.setProperty("octo.noEarlyBootstrap", "true");

        try {
            GameBootstrap.Outcome outcome = GameBootstrap.run(game);

            assertFalse(outcome.bootstrapped());
            assertNull(outcome.failure(), "declining to do it is not a failure to do it");

            Class<?> bootstrap = Class.forName("net.minecraft.server.Bootstrap", true, game);
            assertEquals(0, bootstrap.getField("calls").getInt(null),
                    "Octo must not have touched the game's bootstrap at all");
        } finally {
            System.clearProperty("octo.noEarlyBootstrap");
        }
    }

    @Test
    @DisplayName("a game with no bootstrap at all is not a failure")
    void nothingToBootstrap() {
        GameBootstrap.Outcome outcome = GameBootstrap.run(new ByteClassLoader(getClass().getClassLoader()));

        assertFalse(outcome.bootstrapped());
        assertNull(outcome.failure(), "an old version has nothing to bootstrap, which is not a problem");
        assertFalse(outcome.retried());
    }

    private static ClassLoader load(Map<String, String> sources) {
        return new ByteClassLoader(getSystemLoader()).defineAll(SourceCompiler.compile(sources, List.of()));
    }

    private static ClassLoader getSystemLoader() {
        return GameBootstrapTest.class.getClassLoader();
    }
}
