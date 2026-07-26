package studios.milkdromeda.octo.util;

import java.io.PrintStream;
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
        error.printStackTrace(System.err);
    }

    private void log(Level level, String message, Object... args) {
        if (level.ordinal() < threshold.ordinal()) {
            return;
        }

        PrintStream out = level.ordinal() >= Level.WARN.ordinal() ? System.err : System.out;
        out.println("[" + TIME.format(LocalTime.now()) + "] [Octo/" + name + "/" + level + "] " + format(message, args));
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
}
