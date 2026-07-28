package net.neoforged.fml.event.lifecycle;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * A phase whose work NeoForge would have spread across threads.
 *
 * <p>{@code enqueueWork} is the single most-called method on any of these
 * events: NeoForge dispatches mod setup on a worker thread and gives mods this
 * to get back onto the main one, so anything touching a registry or the render
 * system goes through it. Octo runs mod setup on the calling thread already, so
 * the work simply runs — but it has to run <em>and hand back a future</em>,
 * because the caller usually does something with it. A {@code void} version of
 * this method, which is what Octo had, is a {@code NoSuchMethodError} at every
 * {@code enqueueWork(...).thenAccept(...)} in the ecosystem.
 *
 * <p>Failures are captured in the returned future rather than thrown, which is
 * what the real implementation does, and logged against the mod, which it does
 * not: a mod that ignores the future would otherwise lose the error entirely.
 */
public abstract class ParallelDispatchEvent extends ModLifecycleEvent {
    private static final OctoLog LOG = OctoLog.of(ParallelDispatchEvent.class);

    protected ParallelDispatchEvent(String modId) {
        super(modId);
    }

    public CompletableFuture<Void> enqueueWork(Runnable work) {
        return enqueueWork(() -> {
            work.run();
            return null;
        });
    }

    public <T> CompletableFuture<T> enqueueWork(Supplier<T> work) {
        try {
            return CompletableFuture.completedFuture(work.get());
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.error("{}: work enqueued during {} threw {}", getModId(),
                    getClass().getSimpleName(), Failures.describe(e));
            OctoLog.detail(e);
            return CompletableFuture.failedFuture(e);
        }
    }
}
