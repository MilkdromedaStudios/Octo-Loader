package studios.milkdromeda.octo.hook;

import java.util.function.IntConsumer;

import studios.milkdromeda.octo.report.GameWatch;
import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Where Minecraft's own {@code System.exit} arrives before the JVM acts on it.
 *
 * <p>Minecraft, on a crash, writes its report and calls {@code System.exit(-1)}
 * from the thread that caught the failure. Octo used to meet that in a shutdown
 * hook, which held the process open long enough for the report window to be
 * read — on paper. In practice a player saw the window for about a second and
 * then nothing but the launcher's "Exit code: -1", while Octo's own log went on
 * recording a JVM held open for the full half hour.
 *
 * <p>Both were true. A shutdown hook can keep the process alive; it cannot keep
 * the windowing toolkit alive. Windows' AWT registers a shutdown hook of its
 * own, hooks all run at once, and when that one finishes the toolkit thread is
 * gone and every window it owned goes with it. The JVM is still there. Nothing
 * is on screen. (X11 does not do this, which is exactly why it took a player on
 * Windows to find it.)
 *
 * <p>So the window is no longer put up during shutdown. The game's exit calls
 * are rewritten as the game is loaded to arrive here first, which is a normal
 * moment in a whole JVM: the toolkit is running, the window opens and stays,
 * and the real exit is what happens after the player closes it.
 *
 * @see studios.milkdromeda.octo.transform.GameExitTransformer
 */
public final class GameExit {
    private static final OctoLog LOG = OctoLog.of(GameExit.class);

    /** The real exits, held apart from the calls so a test can watch instead. */
    private static final IntConsumer REAL_EXIT = System::exit;
    private static final IntConsumer REAL_HALT = status -> Runtime.getRuntime().halt(status);

    private static IntConsumer exit = REAL_EXIT;
    private static IntConsumer halt = REAL_HALT;

    private static boolean settled;

    private GameExit() {
    }

    /** Called instead of {@code System.exit} and {@code Runtime.exit} in game code. */
    public static void exit(int status) {
        settle(status);
        exit.accept(status);
    }

    /**
     * Called instead of {@code Runtime.halt} in game code.
     *
     * <p>Minecraft halts when its own shutdown has run out of patience. That is
     * a real exit and Octo does not argue with it; it is only worth passing
     * through here so a crash that ends this way is still shown.
     */
    public static void halt(int status) {
        settle(status);
        halt.accept(status);
    }

    /**
     * Says what happened, once, while there is still a JVM to say it in.
     *
     * <p>Synchronized rather than a flag test: a second thread reaching an exit
     * while the first is holding the window open must wait for it, not run
     * ahead and take the process — and the window — down.
     */
    private static synchronized void settle(int status) {
        if (settled) {
            return;
        }

        settled = true;

        try {
            GameWatch.gameExiting(status);
        } catch (Throwable e) {
            // The game is leaving either way. A failure in the reporting is not
            // worth turning into a second one on top of it.
            Failures.rethrowIfFatal(e);
            LOG.debug("could not report the game's exit: {}", e.toString());
        }
    }

    /** Test seam: watches the exits instead of taking them. */
    static synchronized void takeOverForTest(IntConsumer exit, IntConsumer halt) {
        GameExit.exit = exit;
        GameExit.halt = halt;
        settled = false;
    }

    /** Test seam: hands the exits back to the JVM. */
    static synchronized void releaseForTest() {
        exit = REAL_EXIT;
        halt = REAL_HALT;
        settled = false;
    }
}
