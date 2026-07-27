package studios.milkdromeda.octo.util;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Minimal logger. The loader runs before any mod (and therefore before any mod's
 * logging framework) exists, so it cannot depend on one.
 */
public final class OctoLog {
    public enum Level { TRACE, DEBUG, INFO, WARN, ERROR }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);
    private static Level threshold = Level.INFO;
    private static PrintStream file;

    private final String name;

    private OctoLog(String name) {
        this.name = name;
    }

    public static OctoLog of(Class<?> owner) {
        return new OctoLog(owner.getSimpleName());
    }

    public static OctoLog of(String name) {
        return new OctoLog(name);
    }

    public static void setThreshold(Level level) {
        threshold = level;
    }

    public static Level threshold() {
        return threshold;
    }

    /** Starts a persistent log before mod discovery or game inspection begins. */
    public static synchronized void openFile(Path path) throws IOException {
        if (file != null) {
            file.close();
        }

        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        file = new PrintStream(Files.newOutputStream(path, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), true, StandardCharsets.UTF_8);
    }

    public boolean isDebugEnabled() {
        return threshold.ordinal() <= Level.DEBUG.ordinal();
    }

    public void trace(String message, Object... args) {
        log(Level.TRACE, message, args);
    }

    public void debug(String message, Object... args) {
        log(Level.DEBUG, message, args);
    }

    public void info(String message, Object... args) {
        log(Level.INFO, message, args);
    }

    public void warn(String message, Object... args) {
        log(Level.WARN, message, args);
    }

    public void error(String message, Object... args) {
        log(Level.ERROR, message, args);
    }

    public void error(String message, Throwable error) {
        log(Level.ERROR, message);
        writeThrowable(error);
    }

    private synchronized void log(Level level, String message, Object... args) {
        String line = "[" + TIME.format(LocalTime.now()) + "] [Octo/" + name + "/" + level + "] "
                + format(message, args);

        if (level.ordinal() >= threshold.ordinal()) {
            PrintStream out = level.ordinal() >= Level.WARN.ordinal() ? System.err : System.out;
            out.println(line);
        }

        synchronized (OctoLog.class) {
            if (file != null) {
                // The console threshold is only a display preference. The persistent log is
                // diagnostic evidence, so retain every message even when debug output is hidden.
                file.println(line);
            }
        }
    }

    /** {@code {}} placeholder substitution, the one piece of SLF4J worth keeping. */
    static String format(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }

        StringBuilder out = new StringBuilder(message.length() + 32);
        int arg = 0;
        int i = 0;

        while (i < message.length()) {
            int next = message.indexOf("{}", i);

            if (next < 0 || arg >= args.length) {
                out.append(message, i, message.length());
                break;
            }

            out.append(message, i, next).append(args[arg++]);
            i = next + 2;
        }

        return out.toString();
    }

    public static synchronized void writeThrowable(Throwable error) {
        if (file != null) {
            error.printStackTrace(file);
        }
        error.printStackTrace(System.err);
    }

    /**
     * Records the whole stack trace of a contained failure.
     *
     * <p>It goes to the persistent log unconditionally, because a mod that failed
     * quietly at startup is exactly what someone will be trying to diagnose later,
     * but only reaches the console in debug: fifty stack traces on stderr during a
     * normal launch bury the one line that matters.
     */
    public static synchronized void detail(Throwable error) {
        if (file != null) {
            error.printStackTrace(file);
        }

        if (threshold.ordinal() <= Level.DEBUG.ordinal()) {
            error.printStackTrace(System.err);
        }
    }
}
