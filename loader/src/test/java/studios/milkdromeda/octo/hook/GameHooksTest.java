package studios.milkdromeda.octo.hook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Work that must come after the mods' client setup waits for it, off the game's own thread. */
class GameHooksTest {
    @AfterEach
    void reset() {
        GameHooks.reset();
    }

    @Test
    @DisplayName("with nothing armed there is nothing to wait for")
    void nothingArmed() {
        assertTrue(GameHooks.awaitClientPhases(10));
    }

    @Test
    @DisplayName("a worker waits until the client phases have run, then sees what they did")
    void workerWaits() throws Exception {
        AtomicBoolean setUp = new AtomicBoolean();
        GameHooks.arm(() -> setUp.set(true));

        CompletableFuture<Boolean> worker = CompletableFuture.supplyAsync(() -> {
            boolean done = GameHooks.awaitClientPhases(10_000);
            return done && setUp.get();
        });

        Thread.sleep(100);
        assertFalse(worker.isDone(), "the worker is still waiting before the game has started");

        GameHooks.clientStarted(new Object());
        assertTrue(worker.get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("the game's own thread never waits for work only it can run")
    void gameThreadDoesNotWait() {
        GameHooks.clientConstructing(new Object());
        GameHooks.arm(() -> { });

        long started = System.nanoTime();
        assertFalse(GameHooks.awaitClientPhases(5_000));
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
    }

    @Test
    @DisplayName("a wait that runs out says so instead of hanging")
    void timeout() {
        GameHooks.arm(() -> { });
        assertFalse(CompletableFuture.supplyAsync(() -> GameHooks.awaitClientPhases(50)).join());
    }
}
