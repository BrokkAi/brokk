package ai.brokk.concurrent;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.Context;
import ai.brokk.context.ContextDelta;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class UserActionManagerCancellationTest {

    @TempDir
    Path tempDir;

    private UserActionManager uam;

    @AfterEach
    void tearDown() {
        if (uam != null) {
            uam.shutdownAndAwait(1000);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void submitLlmAction_interruptBeforeContextDeltaBetween_treatedAsCancellation_notRuntimePermitError()
            throws Exception {
        var console = new TestConsoleIO();
        var analyzer = new TestAnalyzer();
        var cm = new TestContextManager(tempDir, console, analyzer);

        uam = new UserActionManager(console);

        CompletableFuture<Void> future = uam.submitLlmAction(() -> {
            // Simulate the user hitting Stop just before ContextDelta.between is invoked.
            // This sets the interrupt flag on the userExecutor thread.
            Thread.currentThread().interrupt();

            Context from = new Context(cm);
            Context to = from; // identical contexts are fine; we only care about the path through between(...)

            // This join will see the pre-existing interrupt when newVirtualThreadExecutor's Semaphore.acquire runs,
            // translating it into a CancellationException("Interrupted while acquiring virtual-thread permit").
            ContextDelta.between(from, to).join();
        });

        // The future should complete exceptionally because of the uncaught CancellationException
        ExecutionException execEx = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        Throwable top = execEx.getCause();

        // Walk the cause chain looking for specific exception types/messages.
        boolean hasCancellation = false;
        boolean hasOldRuntimePermitError = false;

        Throwable current = top;
        while (current != null) {
            if (current instanceof CancellationException) {
                hasCancellation = true;
            }
            // The old bug wrapped the InterruptedException in a RuntimeException with this exact message
            if (current instanceof RuntimeException
                    && "Interrupted while acquiring virtual-thread permit".equals(current.getMessage())) {
                hasOldRuntimePermitError = true;
            }
            current = current.getCause();
        }

        assertTrue(hasCancellation, "Expected CancellationException somewhere in cause chain to signal cancellation");
        assertFalse(
                hasOldRuntimePermitError,
                "Did not expect RuntimeException(\"Interrupted while acquiring virtual-thread permit\") in cause chain; that was the old, noisy behavior");
    }
}
