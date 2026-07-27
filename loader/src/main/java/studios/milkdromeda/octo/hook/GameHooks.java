package studios.milkdromeda.octo.hook;

import java.util.concurrent.atomic.AtomicBoolean;

import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Where the running game calls back into the loader.
 *
 * <p>Octo used to run every phase of every mod's lifecycle before Minecraft's
 * {@code main} was even invoked. For a Forge mod that is close enough, but a
 * Fabric client mod's {@code onInitializeClient} is specified to run <em>during</em>
 * the game's construction — which is why the first thing so many of them do is
 * call {@code Minecraft.getInstance()}. Called too early that returns null, the
 * mod throws, and the player sees a loader that reports the mod as loaded and a
 * game with no sign of it.
 *
 * <p>So the loader hooks the game's own constructor with a mixin of its own and
 * dispatches the client phases from there, which is where Fabric dispatches
 * them. The rest of the lifecycle still runs beforehand.
 */
public final class GameHooks {
    private static final OctoLog LOG = OctoLog.of(GameHooks.class);

    private static volatile Runnable pending;
    private static final AtomicBoolean fired = new AtomicBoolean();

    private GameHooks() {
    }

    /** Registers the work to do once the game exists. */
    public static void arm(Runnable action) {
        pending = action;
        fired.set(false);
    }

    public static boolean isArmed() {
        return pending != null && !fired.get();
    }

    /**
     * Called from the mixin at the end of the client's constructor.
     *
     * @param game the game instance, which mods reach through
     *             {@code FabricLoader.getInstance().getGameInstance()}
     */
    public static void clientStarted(Object game) {
        if (OctoRuntime.isRunning()) {
            OctoRuntime.get().gameInstance(game);
        }

        run("the game finished starting");
    }

    /**
     * Runs the deferred phases anyway.
     *
     * <p>The hook depends on a mixin applying to a class whose name Octo has to
     * guess for a version it may never have seen. When that guess is wrong the
     * mods must still be initialised, late and loudly, rather than not at all.
     */
    public static void runIfPending(String reason) {
        if (isArmed()) {
            LOG.warn("the game never called back, so the client phases are running now instead ({})", reason);
            run(reason);
        }
    }

    private static void run(String reason) {
        if (!fired.compareAndSet(false, true)) {
            return;
        }

        Runnable action = pending;
        pending = null;

        if (action == null) {
            return;
        }

        LOG.debug("dispatching the deferred client phases: {}", reason);

        try {
            action.run();
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.error("the deferred client phases failed: {}", Failures.describe(e));
            OctoLog.detail(e);
        }
    }

    /** Test hook: forgets anything armed. */
    public static void reset() {
        pending = null;
        fired.set(false);
    }
}
