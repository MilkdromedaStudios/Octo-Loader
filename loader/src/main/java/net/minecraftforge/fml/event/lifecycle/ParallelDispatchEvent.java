package net.minecraftforge.fml.event.lifecycle;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Forge's spelling of
 * {@link net.neoforged.fml.event.lifecycle.ParallelDispatchEvent}.
 *
 * <p>Same contract, same reason for it: {@code enqueueWork} returns a future,
 * mods chain onto that future, and a {@code void} version of the method is a
 * {@code NoSuchMethodError} at every one of those call sites.
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
