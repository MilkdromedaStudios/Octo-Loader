package net.minecraftforge.fml;

import java.io.Serializable;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import net.minecraftforge.api.distmarker.Dist;

import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * Forge's way of running code on one side only, from 1.13 to 1.20.
 *
 * <p>Every Forge mod of those years starts its client half through here:
 * {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientInit::new)}.
 * Forge has since dropped the class, and with nothing in its place the call
 * did nothing: a mod loaded and ran with its renderers, model layers, screens
 * and key bindings never set up, which for Wither Storm meant mobs that were
 * there and could not be seen.
 */
public final class DistExecutor {
    private DistExecutor() {
    }

    private static boolean isOn(Dist dist) {
        boolean client = !OctoRuntime.isRunning() || OctoRuntime.get().side() == Side.CLIENT;
        return dist == (client ? Dist.CLIENT : Dist.DEDICATED_SERVER);
    }

    public static <T> T callWhenOn(Dist dist, Supplier<Callable<T>> toRun) {
        return unsafeCallWhenOn(dist, toRun);
    }

    public static <T> T unsafeCallWhenOn(Dist dist, Supplier<Callable<T>> toRun) {
        if (!isOn(dist)) {
            return null;
        }

        try {
            return toRun.get().call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T safeCallWhenOn(Dist dist, Supplier<SafeCallable<T>> toRun) {
        if (!isOn(dist)) {
            return null;
        }

        try {
            return toRun.get().call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void runWhenOn(Dist dist, Supplier<Runnable> toRun) {
        unsafeRunWhenOn(dist, toRun);
    }

    public static void unsafeRunWhenOn(Dist dist, Supplier<Runnable> toRun) {
        if (isOn(dist)) {
            toRun.get().run();
        }
    }

    public static void safeRunWhenOn(Dist dist, Supplier<SafeRunnable> toRun) {
        if (isOn(dist)) {
            toRun.get().run();
        }
    }

    public static <T> T runForDist(Supplier<Supplier<T>> clientTarget, Supplier<Supplier<T>> serverTarget) {
        return unsafeRunForDist(clientTarget, serverTarget);
    }

    public static <T> T unsafeRunForDist(Supplier<Supplier<T>> clientTarget, Supplier<Supplier<T>> serverTarget) {
        return (isOn(Dist.CLIENT) ? clientTarget : serverTarget).get().get();
    }

    public static <T> T safeRunForDist(Supplier<SafeSupplier<T>> clientTarget,
            Supplier<SafeSupplier<T>> serverTarget) {
        return (isOn(Dist.CLIENT) ? clientTarget : serverTarget).get().get();
    }

    /** Marks a lambda Forge checked was a method reference, so that it named no class of the other side. */
    public interface SafeReferent {
    }

    public interface SafeCallable<T> extends SafeReferent, Callable<T>, Serializable {
    }

    public interface SafeSupplier<T> extends SafeReferent, Supplier<T>, Serializable {
    }

    public interface SafeRunnable extends SafeReferent, Runnable, Serializable {
    }
}
