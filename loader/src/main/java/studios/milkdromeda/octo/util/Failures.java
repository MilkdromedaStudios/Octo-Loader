package studios.milkdromeda.octo.util;

import java.lang.reflect.InvocationTargetException;

/**
 * The rule for how far a misbehaving mod is allowed to reach.
 *
 * <p>A mod that throws while it is being constructed or initialised must cost
 * the player that mod, not the game. That means catching {@link Throwable} and
 * not merely {@code Exception}: the failures that actually happen at this point
 * are {@code Error}s — {@code NoClassDefFoundError} from a class that moved,
 * {@code IncompatibleClassChangeError} from one that changed shape, and the
 * bare {@code AssertionError} a mixin accessor throws when it was never applied.
 * None of those are {@code LinkageError}s in every case, and none of them are
 * reasons to refuse to start Minecraft.
 *
 * <p>The exception is a JVM that is already in trouble. There is nothing useful
 * to do after {@code OutOfMemoryError}, and pretending otherwise turns one
 * failure into a hundred confusing ones, so those are rethrown untouched.
 */
public final class Failures {
    private Failures() {
    }

    /** Whether the JVM, rather than the mod, is what went wrong. */
    public static boolean isFatal(Throwable error) {
        return error instanceof OutOfMemoryError
                || error instanceof StackOverflowError
                || error instanceof InternalError
                || error instanceof UnknownError;
    }

    /** Rethrows the failures no loader can contain, and returns for the rest. */
    public static void rethrowIfFatal(Throwable error) {
        if (!isFatal(error)) {
            return;
        }

        if (error instanceof Error fatal) {
            throw fatal;
        }

        throw new IllegalStateException(error);
    }

    /**
     * Strips the wrappers reflection and class initialisation add.
     *
     * <p>{@code ExceptionInInitializerError} and {@code InvocationTargetException}
     * both hide the only interesting part of the failure one level down, which is
     * why so many loader logs say nothing more than the wrapper's own name.
     */
    public static Throwable unwrap(Throwable error) {
        Throwable current = error;

        while (current.getCause() != null
                && (current instanceof InvocationTargetException
                        || current instanceof ExceptionInInitializerError)) {
            current = current.getCause();
        }

        return current;
    }

    /**
     * A one-line description that keeps the cause chain.
     *
     * <p>{@code java.lang.ExceptionInInitializerError} on its own tells nobody
     * anything; the answer is always in the cause.
     */
    public static String describe(Throwable error) {
        StringBuilder out = new StringBuilder(error.toString());
        Throwable cause = error.getCause();
        int depth = 0;

        while (cause != null && depth < 4) {
            out.append(" <- ").append(cause);
            cause = cause.getCause();
            depth++;
        }

        return out.toString();
    }

    /** Wraps for the APIs that still insist on a checked exception. */
    public static Exception asException(Throwable error) {
        return error instanceof Exception exception ? exception : new RuntimeException(error);
    }
}
