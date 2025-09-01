package io.github.jbellis.brokk.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

public class SlidingWindowCacheTest {

    @Test
    public void testEvictOutsideWindowRetainsUnsaved() {
        // Create cache that can hold entries initially (window not set)
        var cache = new SlidingWindowCache<Integer, SlidingWindowCache.Disposable>(10, 5);

        // Put several entries (all accepted when window isn't set)
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            cache.put(idx, new SlidingWindowCache.Disposable() {
                @Override
                public void dispose() {
                    // no-op
                }

                @Override
                public boolean hasUnsavedChanges() {
                    // Mark some items as unsaved (to be retained during eviction)
                    return idx == 0 || idx == 3;
                }
            });
        }

        // Now set a window that would normally evict items outside [2..4]
        cache.updateWindowCenter(4, 5);

        Set<Integer> cached = cache.getCachedKeys();

        // 0 is outside the window but marked as unsaved -> should be retained
        assertTrue(cached.contains(0), "Key 0 should be retained because it is unsaved");
        // 1 is outside the window and not unsaved -> should be evicted
        assertFalse(cached.contains(1), "Key 1 should be evicted");
    }

    @Test
    public void testClearSchedulesDisposalOnEdt() throws Exception {
        var cache = new SlidingWindowCache<Integer, SlidingWindowCache.Disposable>(10, 5);
        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] calledOnEdt = new boolean[1];

        cache.put(1, new SlidingWindowCache.Disposable() {
            @Override
            public void dispose() {
                calledOnEdt[0] = SwingUtilities.isEventDispatchThread();
                latch.countDown();
            }
        });

        // clear() will collect values and call disposeDeferred (schedules disposal)
        cache.clear();

        // Ensure EDT processes any pending tasks
        SwingUtilities.invokeAndWait(() -> {});

        boolean invoked = latch.await(2, TimeUnit.SECONDS);
        assertTrue(invoked, "dispose should be invoked");
        assertTrue(calledOnEdt[0], "dispose should run on EDT");
    }
}
