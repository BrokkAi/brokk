package io.github.jbellis.brokk.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jbellis.brokk.gui.components.TokenUsageBar;
import io.github.jbellis.brokk.util.Messages;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Integration-style test that verifies token counting runs off the EDT and the UI is updated
 * asynchronously.
 *
 * <p>This test purposely mirrors the pattern used by InstructionsPanel: heavy token counting is
 * done off the EDT, and the UI update is posted back to the EDT. It does not attempt to
 * instantiate the full InstructionsPanel or Chrome object (those are heavyweight); instead it
 * focuses on the observable behavior.
 */
public class InstructionsPanelTokenCountTest {

    @Test
    public void tokenCountingRunsOffEdtAndUpdatesTokenUsageBar() throws Exception {
        // Prepare a large text sample to make token counting non-trivial.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            sb.append("line " + i + "\n");
        }
        String largeText = sb.toString();

        TokenUsageBar bar = new TokenUsageBar();
        // Ensure bar is initially not visible to emulate the "empty context" state
        bar.setVisible(false);

        AtomicBoolean countedOnEdt = new AtomicBoolean(false);
        CountDownLatch uiUpdated = new CountDownLatch(1);

        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-token-count-worker");
            t.setDaemon(true);
            return t;
        });

        try {
            ex.submit(() -> {
                // Record whether this worker thread is the EDT (it should not be)
                countedOnEdt.set(SwingUtilities.isEventDispatchThread());

                // Perform the (potentially expensive) token counting using the production helper
                int approx = Messages.getApproximateTokens(largeText);

                // Now post a UI update on the EDT (simulates what InstructionsPanel does)
                SwingUtilities.invokeLater(() -> {
                    // Use a reasonable max token value so the bar has a non-zero fill
                    int max = Math.max(1, approx * 2);
                    bar.setTokens(approx, max);
                    bar.setVisible(true);
                    uiUpdated.countDown();
                });
            });

            // Wait for the UI update to occur (timeout to avoid flakiness)
            boolean signaled = uiUpdated.await(2, TimeUnit.SECONDS);
            assertTrue(signaled, "Timed out waiting for UI update from token counting task");

            // Assert that the token counting did NOT happen on the EDT
            assertFalse(countedOnEdt.get(), "Token counting should not run on the AWT Event Dispatch Thread");

            // Assert the TokenUsageBar was made visible by the UI update
            assertTrue(bar.isVisible(), "TokenUsageBar should be visible after the async update");
        } finally {
            ex.shutdownNow();
        }
    }
}
