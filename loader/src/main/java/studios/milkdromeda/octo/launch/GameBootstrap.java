package studios.milkdromeda.octo.launch;

import java.lang.reflect.Field;
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
 * <p>That guard is also the trap. {@code Bootstrap.bootStrap()} sets its "done"
 * flag <em>before</em> it does the work, so a call that throws half way through
 * leaves the flag set and the registries part-built — and the game's own call,
 * the one that would have done it properly, then does nothing at all. What the
 * player sees is not the original failure but a null default entry much later:
 * {@code Cannot invoke "net.minecraft.core.Holder$Reference.value()" because
 * "this.defaultValue" is null}, during "Initializing game", with nothing in the
 * crash report to say why. So when the early call fails, the flag is put back:
 * the game retries the bootstrap itself, and either succeeds or fails where it
 * can say what happened.
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

    /** The flags Minecraft has used to record that the bootstrap has run. */
    private static final List<String> DONE_FLAGS = List.of("isBootstrapped", "alreadyRegistered");

    /**
     * The class whose loading fills in the block registry's default entry.
     *
     * <p>Reaching it after the bootstrap is how Octo tells a bootstrap that
     * finished from one that only stopped throwing: {@code air} is registered
     * from here, and the registry's default is set as it is registered.
     */
    private static final List<String> BLOCKS = List.of(
            "net.minecraft.world.level.block.Blocks",
            "net.minecraft.block.Blocks",
            "net.minecraft.init.Blocks");

    private record Step(String className, String methodName) {
    }

    /**
     * What the prologue managed.
     *
     * @param bootstrapped whether the registries were built
     * @param failure      what stopped it, or null
     * @param retried      whether the game will now do the bootstrap itself
     */
    record Outcome(boolean bootstrapped, Throwable failure, boolean retried) {
        static final Outcome NOT_FOUND = new Outcome(false, null, false);

        static Outcome done() {
            return new Outcome(true, null, false);
        }
    }

    private GameBootstrap() {
    }

    /**
     * Runs the game's own start-up prologue, as far as it can be found.
     *
     * @return what happened, so the launch can report it rather than let it turn
     *         into an unrelated-looking crash later
     */
    static Outcome run(ClassLoader classLoader) {
        invokeFirst(VERSION, classLoader);
        Outcome outcome = invokeFirst(REGISTRIES, classLoader);

        if (!outcome.bootstrapped()) {
            if (outcome.failure() == null) {
                // Every pre-1.13 version and every synthetic game jar lands here,
                // and for those it is correct: there is nothing to bootstrap.
                LOG.debug("no game bootstrap entry point found; mods run against the game as it is");
            }

            return outcome;
        }

        Throwable registries = verifyRegistries(classLoader);

        if (registries != null) {
            LOG.warn("the game's bootstrap returned but its registries are not usable: {}",
                    Failures.describe(registries));
            return new Outcome(false, registries, false);
        }

        LOG.info("game registries bootstrapped; mods can use them from their initialiser");
        return Outcome.done();
    }

    private static Outcome invokeFirst(List<Step> steps, ClassLoader classLoader) {
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
                return Outcome.done();
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                Throwable cause = Failures.unwrap(e);
                LOG.warn("{}.{}() failed: {}", step.className(), step.methodName(), Failures.describe(cause));
                OctoLog.detail(cause);
                return new Outcome(false, cause, clearDoneFlag(type));
            }
        }

        return Outcome.NOT_FOUND;
    }

    /**
     * Puts the game's "bootstrap has run" flag back after a failed attempt.
     *
     * <p>Without this the game skips the retry it would otherwise do, and the
     * half-built registries surface much later as a null default entry with no
     * trace of the original failure.
     *
     * @return whether the game will now try the bootstrap again itself
     */
    private static boolean clearDoneFlag(Class<?> bootstrap) {
        for (String name : DONE_FLAGS) {
            Field flag;

            try {
                flag = bootstrap.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                continue;
            }

            if (flag.getType() != boolean.class) {
                continue;
            }

            try {
                flag.setAccessible(true);
                flag.setBoolean(null, false);
                LOG.warn("the game's bootstrap did not finish; its {} flag has been cleared so Minecraft "
                        + "runs it again itself", name);
                return true;
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.debug("could not clear {}.{}: {}", bootstrap.getName(), name, e.toString());
                return false;
            }
        }

        LOG.warn("the game's bootstrap did not finish and Octo could not find the flag that would make "
                + "Minecraft retry it; the registries may be incomplete");
        return false;
    }

    /**
     * Checks that the bootstrap actually built something, rather than returning.
     *
     * <p>A registry whose default entry was never registered is the failure this
     * whole class exists to prevent, and it is invisible until the game asks for
     * that default and gets nothing. Touching the block table here is the
     * cheapest way to find out now, while the real cause is still in reach:
     * loading it either works, or it throws with the reason.
     *
     * @return what is wrong, or null if the registries look built
     */
    private static Throwable verifyRegistries(ClassLoader classLoader) {
        for (String name : BLOCKS) {
            try {
                Class.forName(name, true, classLoader);
                return null;
            } catch (ClassNotFoundException e) {
                continue;
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                return Failures.unwrap(e);
            }
        }

        // No block table under any of its names: an old version, or a game jar
        // that is not Minecraft. Nothing to verify, and nothing wrong.
        return null;
    }
}
