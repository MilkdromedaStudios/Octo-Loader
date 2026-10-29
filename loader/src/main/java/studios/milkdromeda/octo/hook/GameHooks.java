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
    /** What runs as the client starts being built, rather than once it is. */
    private static volatile Runnable early;
    private static final AtomicBoolean firedEarly = new AtomicBoolean();
    /** Opened once the deferred phases have run, for work that must come after them. */
    private static volatile java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
    /** The thread building the client, which runs the deferred phases and so must never wait for them. */
    private static volatile Thread gameThread;

    private GameHooks() {
    }

    /** Registers the work to do once the game exists. */
    public static void arm(Runnable action) {
        pending = action;
        fired.set(false);
        finished = new java.util.concurrent.CountDownLatch(1);
    }

    /**
     * Registers the work to do part-way into the client's constructor, before
     * its options and window exist: Fabric's client initialisers. Sodium's
     * mixin into {@code setFullscreen}, further into that constructor, reads
     * the config its initialiser loads; run at the end of the constructor
     * instead, there was no config yet and the game crashed starting.
     */
    public static void armEarly(Runnable action) {
        early = action;
        firedEarly.set(false);
    }

    /** Called from the mixin just before the client builds its options. */
    public static void clientConstructing(Object game) {
        gameThread = Thread.currentThread();

        if (OctoRuntime.isRunning()) {
            OctoRuntime.get().gameInstance(game);
        }

        runEarly("the game began starting");
    }

    private static void runEarly(String reason) {
        if (!firedEarly.compareAndSet(false, true)) {
            return;
        }

        Runnable action = early;
        early = null;

        if (action == null) {
            return;
        }

        LOG.debug("dispatching the early client phases: {}", reason);

        try {
            action.run();
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.error("the early client phases failed: {}", Failures.describe(e));
            OctoLog.detail(e);
        }
    }

    public static boolean isArmed() {
        return pending != null && !fired.get() || early != null && !firedEarly.get();
    }

    /**
     * Called from the mixin at the end of the client's constructor.
     *
     * @param game the game instance, which mods reach through
     *             {@code FabricLoader.getInstance().getGameInstance()}
     */
    public static void clientStarted(Object game) {
        gameThread = Thread.currentThread();

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
        // Whatever was meant to run earlier and did not, runs first.
        runEarly(reason);

        if (!fired.compareAndSet(false, true)) {
            return;
        }

        Runnable action = pending;
        pending = null;

        try {
            if (action != null) {
                LOG.debug("dispatching the deferred client phases: {}", reason);
                action.run();
            }
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.error("the deferred client phases failed: {}", Failures.describe(e));
            OctoLog.detail(e);
        } finally {
            finished.countDown();
        }
    }

    /**
     * Waits, from a thread other than the one building the client, until the
     * deferred client phases have run.
     *
     * <p>NeoForge and Forge set their mods up while the game loads its first
     * resources, ahead of the models: a mod makes the models it will ask for
     * in its client setup — Create makes every partial model there — and asks
     * when the models are read. Here the client phases run as the client's
     * constructor ends, while that first load is already under way on other
     * threads, so whatever needs them first waits for them.
     *
     * @return whether they have run, or there were none to run
     */
    public static boolean awaitClientPhases(long timeoutMillis) {
        // Armed and not yet run, or running now on the game's thread.
        boolean outstanding = pending != null || fired.get() && finished.getCount() > 0;

        if (!outstanding) {
            return true;
        }

        if (Thread.currentThread() == gameThread) {
            return false;
        }

        try {
            boolean done = finished.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);

            if (!done) {
                LOG.warn("the client phases had not run after {} s; going on without them", timeoutMillis / 1000);
            }

            return done;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Test hook: forgets anything armed. */
    public static void reset() {
        pending = null;
        fired.set(false);
        finished = new java.util.concurrent.CountDownLatch(1);
        gameThread = null;
        early = null;
        firedEarly.set(false);
    }
}
