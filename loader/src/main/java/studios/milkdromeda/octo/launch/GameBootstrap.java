package studios.milkdromeda.octo.launch;

import java.lang.reflect.Method;
import java.util.List;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Brings the game's registries up before any mod initialiser runs.
 *
 * <p>Minecraft's own {@code main} detects its version and calls
 * {@code Bootstrap.bootStrap()} before anything touches a registry. Octo runs
 * every mod's initialiser first and hands over to {@code main} afterwards, so
 * mods reached registries that did not exist yet and got
 * {@code IllegalArgumentException: Not bootstrapped (called from registry
 * minecraft:game_event)} — which then cascaded, because a failed
 * {@code BuiltInRegistries} class initialiser stays failed for the life of the
 * JVM and every later mod touching it got {@code NoClassDefFoundError: Could
 * not initialize class} instead.
 *
 * <p>The other loaders do not have this problem because their mod initialisers
 * are invoked from inside the running game rather than before it. Doing the
 * bootstrap here is the same ordering by a shorter route, and it is safe to do
 * twice: the game guards both calls, so its own {@code main} finds the work
 * already done and moves on.
 *
 * <p>Mixins are installed before this runs, so the game classes loaded here are
 * woven on the way in, exactly as they would be later.
 */
final class GameBootstrap {
    private static final OctoLog LOG = OctoLog.of(GameBootstrap.class);

    /** Version detection, oldest name last. */
    private static final List<Step> VERSION = List.of(
            new Step("net.minecraft.SharedConstants", "tryDetectVersion"),
            new Step("net.minecraft.SharedConstants", "getCurrentVersion"));

    /** Registry bootstrap, oldest name last. */
    private static final List<Step> REGISTRIES = List.of(
            new Step("net.minecraft.server.Bootstrap", "bootStrap"),
            new Step("net.minecraft.server.Bootstrap", "register"),
            new Step("net.minecraft.init.Bootstrap", "register"));

    private record Step(String className, String methodName) {
    }

    private GameBootstrap() {
    }

    /**
     * Runs the game's own start-up prologue, as far as it can be found.
     *
     * @return whether the registries were bootstrapped
     */
    static boolean run(ClassLoader classLoader) {
        invokeFirst(VERSION, classLoader);
        boolean bootstrapped = invokeFirst(REGISTRIES, classLoader);

        if (bootstrapped) {
            LOG.info("game registries bootstrapped; mods can use them from their initialiser");
        } else {
            // Every pre-1.13 version and every synthetic game jar lands here, and
            // for those it is correct: there is nothing to bootstrap.
            LOG.debug("no game bootstrap entry point found; mods run against the game as it is");
        }

        return bootstrapped;
    }

    private static boolean invokeFirst(List<Step> steps, ClassLoader classLoader) {
        for (Step step : steps) {
            Class<?> type;

            try {
                type = Class.forName(step.className(), false, classLoader);
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }

            Method method;

            try {
                method = type.getDeclaredMethod(step.methodName());
            } catch (NoSuchMethodException e) {
                continue;
            }

            try {
                method.setAccessible(true);
                method.invoke(null);
                LOG.debug("called {}.{}()", step.className(), step.methodName());
                return true;
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                Throwable cause = Failures.unwrap(e);
                // Worth a warning rather than a failure: the game will try again
                // in its own main, and the mods that need registries will say so.
                LOG.warn("{}.{}() failed: {}", step.className(), step.methodName(), Failures.describe(cause));
                OctoLog.detail(cause);
                return false;
            }
        }

        return false;
    }
}
