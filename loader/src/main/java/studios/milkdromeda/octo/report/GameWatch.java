package studios.milkdromeda.octo.report;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Watches the game Octo just started, and keeps its last words on screen.
 *
 * <p>The report window used to disappear a few seconds after it appeared, and
 * the reason was not the window: Minecraft, on a crash, writes its report and
 * calls {@code System.exit(-1)}. That takes down the JVM the window is drawn
 * from, so the one moment a player most needs to read the window is the moment
 * it vanishes — leaving a launcher dialog saying "Exit code: -1" and nothing
 * else.
 *
 * <p>So the exit is where this attaches. A shutdown hook is the last code to run
 * in a dying JVM, and a shutdown hook that blocks holds the process open while
 * it does. This one works out whether the game fell over or was closed on
 * purpose, puts the failure in the window with the rest of the launch's
 * problems, and then holds the JVM until the player closes it.
 *
 * <p>Three things say the game fell over, and the last of them is the one that
 * actually happens, because Minecraft catches its own crashes before anything
 * else can:
 *
 * <ul>
 *   <li>the game's main method threw, which Octo catches;</li>
 *   <li>a thread died of an uncaught exception — recorded and shown, but not
 *       treated as proof: mods lose background threads and the game plays on;</li>
 *   <li>Minecraft wrote a crash report, which names the phase it died in.</li>
 * </ul>
 */
public final class GameWatch {
    private static final OctoLog LOG = OctoLog.of(GameWatch.class);

    /**
     * How long the window may hold a dead JVM open.
     *
     * <p>Long enough to read, copy and act on; short enough that a window left
     * open behind another one does not keep a Java process alive all day.
     */
    private static final long DEFAULT_HOLD_MINUTES = 30;

    /** How long the launch waits for the windowing toolkit before giving up on it. */
    private static final long WARM_UP_TIMEOUT_MILLIS = 5_000;

    private static final AtomicBoolean ARMED = new AtomicBoolean();

    private static LoadingReport report;
    private static Path gameDir;
    private static Path logFile;
    private static long armedAt;

    /**
     * Whether the game is known to have fallen over, rather than suspected of it.
     *
     * <p>Only two things are proof: Minecraft wrote a crash report, or its main
     * method threw. A thread dying of an uncaught exception is worth recording
     * and worth showing, but it is not proof — mods lose background threads and
     * the game plays on — and it must not be the reason a player who quit on
     * purpose is left with a window holding the process open.
     */
    private static volatile boolean certain;

    private GameWatch() {
    }

    /**
     * Starts watching, just before the game is handed control.
     *
     * @param report  the launch's problems, which the crash is added to
     * @param gameDir where Minecraft writes {@code crash-reports}
     * @param logFile Octo's own log, offered in the window
     */
    public static synchronized void arm(LoadingReport report, Path gameDir, Path logFile) {
        if (!ARMED.compareAndSet(false, true)) {
            return;
        }

        GameWatch.report = report;
        GameWatch.gameDir = gameDir;
        GameWatch.logFile = logFile;
        GameWatch.armedAt = System.currentTimeMillis();

        watchThreads();
        warmUpWindowing();

        try {
            Runtime.getRuntime().addShutdownHook(new Thread(GameWatch::atExit, "octo-report"));
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.debug("could not register the shutdown hook: {}", e.toString());
        }
    }

    /**
     * Records a throwable that ended the game.
     *
     * <p>Called for the exception Octo catches out of the game's own main method.
     * Minecraft usually gets there first with its own crash report, in which case
     * this is the same failure said twice and the crash report wins, because it
     * is the one that names the phase.
     */
    public static void crashed(Throwable error) {
        if (report == null || error == null) {
            return;
        }

        Throwable cause = Failures.unwrap(error);
        report.crashed("", stackOf(cause), null);
        certain = true;
        LOG.error("Minecraft stopped: {}", Failures.describe(cause));

        // Opened now rather than left to the exit: the JVM is still running at
        // this point, which is the difference between a window that can be put
        // up and one that cannot.
        if (ReportWindow.show(report, logFile)) {
            ReportWindow.gameStopped(true);
        }
    }

    /**
     * Starts the windowing toolkit now, so a window can still be opened later.
     *
     * <p>A shutdown hook cannot start AWT. The first thing AWT does is register a
     * shutdown hook of its own, and the JVM refuses to accept one from a JVM that
     * is already shutting down — {@code IllegalStateException: Shutdown in
     * progress} — so a crash reported at exit would find the toolkit unusable and
     * the window would never appear. Starting it here, while the game is merely
     * being handed control, costs a thread and a few megabytes and makes the
     * difference between the crash being shown and being lost.
     *
     * <p>Not on macOS. There the game runs with {@code -XstartOnFirstThread} for
     * GLFW, AWT wants that same thread for itself, and bringing it up
     * speculatively can take the game down with it. On macOS the window is
     * therefore only available at exit if the launch already opened one.
     */
    private static void warmUpWindowing() {
        if (GraphicsEnvironment.isHeadless() || Boolean.getBoolean("octo.noReportWindow") || isMacOs()) {
            return;
        }

        Thread warmUp = new Thread(() -> {
            try {
                java.awt.Toolkit.getDefaultToolkit();
                LOG.debug("windowing is ready, so a crash can still be shown as the game exits");
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.debug("windowing is unavailable: {}", e.toString());
            }
        }, "octo-report-warmup");

        // Started on a thread of its own so a toolkit that never comes up cannot
        // stop the game from starting, and waited for so that it is genuinely
        // ready before it is needed: a game that crashes during "Initializing
        // game" can be dead within seconds of this point, and a toolkit still
        // coming up when the JVM starts shutting down is no use to anybody.
        warmUp.setDaemon(true);
        warmUp.start();

        try {
            warmUp.join(WARM_UP_TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * Everything the window needs when a thread dies with nobody catching it.
     *
     * <p>Chained rather than replaced: whatever was there before this — the
     * platform's default, or a handler a mod installed — still runs.
     */
    private static void watchThreads() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                if (report != null && !report.hasCrash()) {
                    report.crashed("running on " + thread.getName(), stackOf(Failures.unwrap(error)), null);
                    ReportWindow.refresh();
                }
            } catch (Throwable ignored) {
                // An exception handler that throws is a crash nobody can report.
            }

            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    /**
     * The last thing this JVM does.
     *
     * <p>Runs on every exit, including the ordinary one where the player quit the
     * game. In that case there is nothing to hold open and the window goes with
     * the process, which is what anyone would expect.
     */
    private static void atExit() {
        try {
            // Minecraft's own report is preferred over anything Octo caught: it
            // is the one that names the phase the game died in, and by the time
            // it exists it is also the more complete of the two.
            readCrashReport().ifPresent(crash -> {
                report.crashed(crash.phase(), crash.detail(), crash.file());
                certain = true;
            });

            if (!report.hasCrash() || !certain) {
                LOG.debug("the JVM is exiting with nothing that proves a crash; the window closes with it");
                ReportWindow.close();
                return;
            }

            LoadingReport.Crash crash = report.crash().orElseThrow();
            LOG.error("Minecraft stopped{}{}",
                    crash.phase().isBlank() ? "" : " while " + crash.phase(),
                    crash.file() == null ? "" : "; its crash report is " + crash.file());
            report.render().lines().forEach(line -> LOG.warn("{}", line));

            hold();
        } catch (Throwable e) {
            // The JVM is already on its way out. Nothing here is worth turning
            // into a second failure on top of the one being reported.
            Failures.rethrowIfFatal(e);
            LOG.debug("the exit watcher gave up: {}", e.toString());
        }
    }

    /** Keeps the window — and so the process — alive while the player reads it. */
    private static void hold() {
        if (GraphicsEnvironment.isHeadless() || Boolean.getBoolean("octo.noReportWindow")) {
            return;
        }

        if (!ReportWindow.show(report, logFile)) {
            LOG.debug("no window to hold open, so the crash stays in the log");
            return;
        }

        ReportWindow.gameStopped(true);
        LOG.info("Minecraft has stopped; the Octo window stays open until it is closed");

        long minutes = Long.getLong("octo.reportHoldMinutes", DEFAULT_HOLD_MINUTES);

        if (minutes <= 0) {
            return;
        }

        if (!ReportWindow.awaitClose(minutes, TimeUnit.MINUTES)) {
            LOG.debug("the report window was still open after {} minute(s); exiting anyway", minutes);
        }
    }

    // ------------------------------------------------------------ crash reports

    /**
     * Minecraft's own account of what happened, if it managed to write one.
     *
     * <p>Only reports written since this launch started count. A crash-reports
     * folder holds every crash the installation has ever had, and yesterday's is
     * not evidence about today.
     */
    static Optional<LoadingReport.Crash> readCrashReport() {
        Path directory = gameDir == null ? null : gameDir.resolve("crash-reports");

        if (directory == null || !Files.isDirectory(directory)) {
            return Optional.empty();
        }

        Path newest;

        try (Stream<Path> files = Files.list(directory)) {
            newest = files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.startsWith("crash-") && name.endsWith(".txt");
                    })
                    .filter(GameWatch::writtenThisLaunch)
                    .max(Comparator.comparingLong(path -> lastModified(path)))
                    .orElse(null);
        } catch (IOException e) {
            LOG.debug("could not look for a crash report in {}: {}", directory, e.toString());
            return Optional.empty();
        }

        return newest == null ? Optional.empty() : Optional.of(parse(newest));
    }

    private static boolean writtenThisLaunch(Path file) {
        // A few seconds of slack, because the clock the file system stamps with
        // is not always the clock this process reads.
        return lastModified(file) >= armedAt - 5_000;
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Pulls the description and the exception out of a Minecraft crash report. */
    static LoadingReport.Crash parse(Path file) {
        List<String> lines;

        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | java.io.UncheckedIOException e) {
            LOG.debug("could not read {}: {}", file, e.toString());
            return new LoadingReport.Crash("", "", file);
        }

        String phase = "";
        List<String> detail = new ArrayList<>();
        boolean collecting = false;

        for (String line : lines) {
            if (phase.isEmpty() && line.startsWith("Description:")) {
                phase = line.substring("Description:".length()).strip();
                continue;
            }

            if (!collecting) {
                // The exception starts at the first line that is a throwable's
                // own line: no indentation, and a type name with a message.
                collecting = !line.isBlank() && !Character.isWhitespace(line.charAt(0))
                        && (line.contains("Exception") || line.contains("Error") || line.contains("Throwable"));

                if (!collecting) {
                    continue;
                }
            }

            // The sections after the stack are the environment dump: useful in a
            // bug report, noise in a window.
            if (line.startsWith("-- ") || line.startsWith("A detailed walkthrough")) {
                break;
            }

            detail.add(line);

            if (detail.size() >= 80) {
                detail.add("    ... the rest is in " + file);
                break;
            }
        }

        return new LoadingReport.Crash(phase, String.join(System.lineSeparator(), detail).strip(), file);
    }

    private static String stackOf(Throwable error) {
        java.io.StringWriter text = new java.io.StringWriter();

        try (java.io.PrintWriter writer = new java.io.PrintWriter(text)) {
            error.printStackTrace(writer);
        }

        return text.toString().lines().limit(80)
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }

    /** Test seam: forgets everything, so a second launch in one JVM can arm again. */
    static synchronized void reset() {
        ARMED.set(false);
        report = null;
        gameDir = null;
        logFile = null;
        certain = false;
    }

    /** Test seam: arms without a shutdown hook or a window. */
    static synchronized void armForTest(LoadingReport report, Path gameDir, long armedAt) {
        GameWatch.report = report;
        GameWatch.gameDir = gameDir;
        GameWatch.armedAt = armedAt;
    }
}
