package io.github.jbellis.brokk.difftool.scroll;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ScrollSyncState} thread-safe state management.
 */
class ScrollSyncStateTest
{
    private ScrollSyncState state;

    @BeforeEach
    void setUp()
    {
        state = new ScrollSyncState();
    }

    @Test
    void initialState()
    {
        assertFalse(state.isUserScrolling(), "Should not be user scrolling initially");
        assertFalse(state.isProgrammaticScroll(), "Should not be programmatic scrolling initially");
        assertFalse(state.hasPendingScroll(), "Should not have pending scroll initially");
        assertEquals(Long.MAX_VALUE, state.getTimeSinceLastUserScroll(), "Should have no user scroll time initially");
    }

    @Test
    void userScrollRecording()
    {
        state.recordUserScroll();
        
        assertTrue(state.isUserScrolling(), "Should be user scrolling after recording");
        assertTrue(state.getTimeSinceLastUserScroll() < 100, "Time since scroll should be very recent");
        assertTrue(state.isUserScrollingWithin(1000), "Should be within time window");
        
        state.clearUserScrolling();
        assertFalse(state.isUserScrolling(), "Should not be user scrolling after clearing");
    }

    @Test
    void programmaticScrollState()
    {
        state.setProgrammaticScroll(true);
        assertTrue(state.isProgrammaticScroll(), "Should be programmatic scrolling");
        
        state.setProgrammaticScroll(false);
        assertFalse(state.isProgrammaticScroll(), "Should not be programmatic scrolling");
    }

    @Test
    void pendingScrollState()
    {
        state.setPendingScroll(true);
        assertTrue(state.hasPendingScroll(), "Should have pending scroll");
        
        assertTrue(state.getAndClearPendingScroll(), "Should return true and clear");
        assertFalse(state.hasPendingScroll(), "Should not have pending scroll after clearing");
        assertFalse(state.getAndClearPendingScroll(), "Should return false when already cleared");
    }

    @Test
    void timeBasedUserScrolling() throws InterruptedException
    {
        state.recordUserScroll();
        assertTrue(state.isUserScrollingWithin(1000), "Should be within 1000ms window");
        
        Thread.sleep(100);
        assertTrue(state.isUserScrollingWithin(1000), "Should still be within 1000ms window");
        
        // Clear the user scrolling flag and test timing only
        state.clearUserScrolling();
        
        // Check the actual time since last scroll to make test more robust
        long timeSince = state.getTimeSinceLastUserScroll();
        assertTrue(timeSince >= 90, "Time since scroll should be at least 90ms, was: " + timeSince);
        assertFalse(state.isUserScrollingWithin(50), "Should not be within 50ms window");
    }

    @Test
    void syncSuppressionLogic()
    {
        // Initially should allow sync
        var result = state.shouldSuppressSync(200);
        assertFalse(result.shouldSuppress(), "Should allow sync initially");
        assertNull(result.reason(), "Should have no reason initially");
        
        // Test programmatic scroll suppression
        state.setProgrammaticScroll(true);
        result = state.shouldSuppressSync(200);
        assertTrue(result.shouldSuppress(), "Should suppress during programmatic scroll");
        assertTrue(result.reason().contains("programmatic"), "Reason should mention programmatic");
        
        state.setProgrammaticScroll(false);
        
        // Test user scroll suppression
        state.recordUserScroll();
        result = state.shouldSuppressSync(200);
        assertTrue(result.shouldSuppress(), "Should suppress during user scroll");
        assertTrue(result.reason().contains("user scrolling"), "Reason should mention user scrolling");
        
        // Clear and test time window
        state.clearUserScrolling();
        result = state.shouldSuppressSync(0);
        assertFalse(result.shouldSuppress(), "Should allow sync after clearing with 0ms window");
    }

    @Test
    void concurrentAccess() throws InterruptedException
    {
        // Test thread safety with multiple threads
        Thread userScrollThread = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                state.recordUserScroll();
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        
        Thread programmaticThread = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                state.setProgrammaticScroll(i % 2 == 0);
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        
        Thread pendingThread = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                state.setPendingScroll(true);
                state.getAndClearPendingScroll();
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        
        userScrollThread.start();
        programmaticThread.start();
        pendingThread.start();
        
        userScrollThread.join(1000);
        programmaticThread.join(1000);
        pendingThread.join(1000);
        
        // Should not crash and state should be consistent
        assertNotNull(state.shouldSuppressSync(100), "Should be able to check suppression after concurrent access");
    }
}