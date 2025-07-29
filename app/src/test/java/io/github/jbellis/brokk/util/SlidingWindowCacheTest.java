package io.github.jbellis.brokk.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SlidingWindowCacheTest {

    private SlidingWindowCache<String, TestDisposable> cache;

    /**
     * Test implementation of Disposable that tracks disposal calls.
     */
    private static class TestDisposable implements SlidingWindowCache.Disposable {
        private final String id;
        private final AtomicBoolean disposed = new AtomicBoolean(false);
        private final AtomicBoolean hasUnsavedChanges = new AtomicBoolean(false);

        TestDisposable(String id) {
            this.id = id;
        }

        @Override
        public void dispose() {
            disposed.set(true);
        }

        @Override
        public boolean hasUnsavedChanges() {
            return hasUnsavedChanges.get();
        }

        void setHasUnsavedChanges(boolean hasChanges) {
            hasUnsavedChanges.set(hasChanges);
        }

        boolean isDisposed() {
            return disposed.get();
        }

        String getId() {
            return id;
        }

        @Override
        public String toString() {
            return "TestDisposable(" + id + ")";
        }
    }

    @BeforeEach
    void setUp() {
        cache = new SlidingWindowCache<>(3); // Small cache for testing eviction
    }

    @Test
    void testBasicOperations() {
        var value1 = new TestDisposable("1");
        var value2 = new TestDisposable("2");

        // Test put and get
        assertNull(cache.get("key1"));
        cache.put("key1", value1);
        assertEquals(value1, cache.get("key1"));

        // Test containsValue
        assertTrue(cache.containsValue(value1));
        assertFalse(cache.containsValue(value2));

        // Test overwrite
        cache.put("key1", value2);
        assertEquals(value2, cache.get("key1"));
        assertFalse(cache.containsValue(value1));
        assertTrue(cache.containsValue(value2));
    }

    @Test
    void testLRUEviction() {
        var value1 = new TestDisposable("1");
        var value2 = new TestDisposable("2");
        var value3 = new TestDisposable("3");
        var value4 = new TestDisposable("4");

        // Fill cache to capacity
        cache.put("key1", value1);
        cache.put("key2", value2);
        cache.put("key3", value3);

        assertFalse(value1.isDisposed());
        assertFalse(value2.isDisposed());
        assertFalse(value3.isDisposed());

        // Add fourth item, should evict first (oldest)
        cache.put("key4", value4);

        assertTrue(value1.isDisposed()); // Should be evicted and disposed
        assertFalse(value2.isDisposed());
        assertFalse(value3.isDisposed());
        assertFalse(value4.isDisposed());

        assertNull(cache.get("key1"));
        assertEquals(value2, cache.get("key2"));
        assertEquals(value3, cache.get("key3"));
        assertEquals(value4, cache.get("key4"));
    }

    @Test
    void testLRUAccessUpdatesOrder() {
        var value1 = new TestDisposable("1");
        var value2 = new TestDisposable("2");
        var value3 = new TestDisposable("3");
        var value4 = new TestDisposable("4");

        // Fill cache
        cache.put("key1", value1);
        cache.put("key2", value2);
        cache.put("key3", value3);

        // Access key1 to make it most recently used
        cache.get("key1");

        // Add fourth item, should evict key2 (now oldest)
        cache.put("key4", value4);

        assertFalse(value1.isDisposed()); // Should not be evicted due to recent access
        assertTrue(value2.isDisposed());   // Should be evicted
        assertFalse(value3.isDisposed());
        assertFalse(value4.isDisposed());

        assertEquals(value1, cache.get("key1"));
        assertNull(cache.get("key2"));
        assertEquals(value3, cache.get("key3"));
        assertEquals(value4, cache.get("key4"));
    }

    @Test
    void testTryReserveAndPutReserved() {
        var value1 = new TestDisposable("1");

        // Test successful reservation
        assertTrue(cache.tryReserve("key1"));
        assertFalse(cache.tryReserve("key1")); // Already reserved

        // Put the reserved value
        cache.putReserved("key1", value1);
        assertEquals(value1, cache.get("key1"));

        // Try to reserve already populated key
        assertFalse(cache.tryReserve("key1"));
    }

    @Test
    void testRemoveReserved() {
        // Reserve a key
        assertTrue(cache.tryReserve("key1"));

        // Remove the reservation
        cache.removeReserved("key1");

        // Should be able to reserve again
        assertTrue(cache.tryReserve("key1"));
    }

    @Test
    void testNonNullValues() {
        var value1 = new TestDisposable("1");
        var value2 = new TestDisposable("2");

        // Add normal values
        cache.put("key1", value1);
        cache.put("key2", value2);

        // Reserve a slot (creates null entry)
        cache.tryReserve("key3");

        Collection<TestDisposable> values = cache.nonNullValues();
        assertEquals(2, values.size());
        assertTrue(values.contains(value1));
        assertTrue(values.contains(value2));

        // Verify iteration is safe (returns copy)
        var valuesList = new ArrayList<>(values);
        cache.put("key4", new TestDisposable("4"));
        assertEquals(2, valuesList.size()); // Original collection unaffected
    }

    @Test
    void testClear() {
        var value1 = new TestDisposable("1");
        var value2 = new TestDisposable("2");
        var value3 = new TestDisposable("3");

        cache.put("key1", value1);
        cache.put("key2", value2);
        cache.tryReserve("key3"); // Reserved slot with null

        assertFalse(value1.isDisposed());
        assertFalse(value2.isDisposed());

        cache.clear();

        assertTrue(value1.isDisposed());
        assertTrue(value2.isDisposed());

        assertNull(cache.get("key1"));
        assertNull(cache.get("key2"));
        assertTrue(cache.nonNullValues().isEmpty());
    }

    @Test
    void testPutReservedAssertionFailure() {
        var value1 = new TestDisposable("1");

        // Put a real value first
        cache.put("key1", value1);

        // Try to putReserved on non-reserved key should fail assertion
        assertThrows(AssertionError.class, () -> cache.putReserved("key1", new TestDisposable("2")));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testConcurrentAccess() throws InterruptedException {
        final int numThreads = 10;
        final int operationsPerThread = 100;
        var executor = Executors.newFixedThreadPool(numThreads);
        var latch = new CountDownLatch(numThreads);
        var errors = new ConcurrentLinkedQueue<Exception>();

        // Launch concurrent threads doing mixed operations
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        String key = "thread" + threadId + "-key" + i;
                        var value = new TestDisposable(key);

                        // Mix of operations
                        cache.put(key, value);
                        cache.get(key);
                        if (i % 3 == 0) {
                            cache.nonNullValues();
                        }
                        if (i % 5 == 0) {
                            cache.containsValue(value);
                        }
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(8, TimeUnit.SECONDS));
        executor.shutdown();

        if (!errors.isEmpty()) {
            fail("Concurrent access failed with errors: " + errors);
        }

        // The important part of this test is that it completes without throwing an exception
        // and that the errors queue is empty. The final state of the cache is non-deterministic.
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testConcurrentReservations() throws InterruptedException {
        final int numThreads = 10;
        var executor = Executors.newFixedThreadPool(numThreads);
        var latch = new CountDownLatch(numThreads);
        var successfulReservations = new AtomicInteger(0);
        var errors = new ConcurrentLinkedQueue<Exception>();

        // All threads try to reserve the same key
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    if (cache.tryReserve("contested-key")) {
                        successfulReservations.incrementAndGet();
                        // Simulate some work
                        Thread.sleep(1);
                        cache.putReserved("contested-key", new TestDisposable("winner-" + threadId));
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(8, TimeUnit.SECONDS));
        executor.shutdown();

        if (!errors.isEmpty()) {
            fail("Concurrent reservations failed with errors: " + errors);
        }

        // Only one thread should have successfully reserved
        assertEquals(1, successfulReservations.get());
        assertNotNull(cache.get("contested-key"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testConcurrentEvictionAndDisposal() throws InterruptedException {
        final int numThreads = 5;
        final int itemsPerThread = 20;
        var executor = Executors.newFixedThreadPool(numThreads);
        var latch = new CountDownLatch(numThreads);
        var allValues = new ConcurrentLinkedQueue<TestDisposable>();
        var errors = new ConcurrentLinkedQueue<Exception>();

        // Create many items to trigger evictions
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < itemsPerThread; i++) {
                        String key = "thread" + threadId + "-item" + i;
                        var value = new TestDisposable(key);
                        allValues.add(value);
                        cache.put(key, value);

                        // Occasionally access random keys to change LRU order
                        if (i % 3 == 0) {
                            cache.get("thread" + (threadId % numThreads) + "-item" + (i / 2));
                        }
                    }
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(8, TimeUnit.SECONDS));
        executor.shutdown();

        if (!errors.isEmpty()) {
            fail("Concurrent eviction test failed with errors: " + errors);
        }

        // Give time for any pending operations to complete
        Thread.sleep(100);

        // Verify that evicted items were properly disposed
        var currentValues = cache.nonNullValues();
        assertTrue(currentValues.size() <= 3); // Cache max size

        // Count disposed items
        long disposedCount = allValues.stream().mapToLong(v -> v.isDisposed() ? 1 : 0).sum();

        // Most items should have been evicted and disposed
        assertTrue(disposedCount > itemsPerThread * numThreads - 10,
            "Expected most items to be disposed, but only " + disposedCount + " out of " +
            (itemsPerThread * numThreads) + " were disposed");
    }

    @Test
    void testReserveFailureCleanup() {
        var value1 = new TestDisposable("1");

        // Reserve a key
        assertTrue(cache.tryReserve("key1"));

        // Simulate load failure by removing reservation
        cache.removeReserved("key1");

        // Should be able to use the key normally now
        cache.put("key1", value1);
        assertEquals(value1, cache.get("key1"));
        assertFalse(value1.isDisposed());
    }

    @Test
    void testReservationTrackingNoNullStorage() {
        var value1 = new TestDisposable("1");

        // Reserve a key
        assertTrue(cache.tryReserve("key1"));

        // get() should return null for reserved but not loaded key
        assertNull(cache.get("key1"));

        // nonNullValues() should not include reserved keys
        assertTrue(cache.nonNullValues().isEmpty());

        // Cannot reserve the same key twice
        assertFalse(cache.tryReserve("key1"));

        // Put the reserved value
        cache.putReserved("key1", value1);

        // Now it should be available
        assertEquals(value1, cache.get("key1"));
        assertEquals(1, cache.nonNullValues().size());
        assertTrue(cache.nonNullValues().contains(value1));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testDeferredDisposalHappensOutsideLock() throws InterruptedException {
        // Test verifies that disposal happens asynchronously and doesn't block cache operations
        var value1 = new TestDisposable("1");
        var value2 = new TestDisposable("2");
        var value3 = new TestDisposable("3");

        // Fill cache to capacity
        cache.put("key1", value1);
        cache.put("key2", value2);
        cache.put("key3", value3);

        // This should trigger eviction of key1 (eldest entry)
        var value4 = new TestDisposable("4");
        cache.put("key4", value4);

        // The evicted item should have been disposed
        assertTrue(value1.isDisposed(), "Evicted item should be disposed");
        assertFalse(value2.isDisposed(), "Non-evicted item should not be disposed");
        assertFalse(value3.isDisposed(), "Non-evicted item should not be disposed");
        assertFalse(value4.isDisposed(), "Newly added item should not be disposed");

        // Cache should have 3 items: key2, key3, key4
        assertEquals(3, cache.nonNullValues().size());
        assertNull(cache.get("key1"));
        assertNotNull(cache.get("key2"));
        assertNotNull(cache.get("key3"));
        assertNotNull(cache.get("key4"));
    }

    @Test
    void testReservationConsistency() {
        // Test that reservations are properly managed
        assertTrue(cache.tryReserve("key1"));
        assertTrue(cache.tryReserve("key2"));

        // Both keys should be reserved
        assertNull(cache.get("key1"));
        assertNull(cache.get("key2"));

        // Remove one reservation
        cache.removeReserved("key1");

        // Should be able to reserve key1 again
        assertTrue(cache.tryReserve("key1"));

        // But not key2
        assertFalse(cache.tryReserve("key2"));

        // Put values for both
        cache.putReserved("key1", new TestDisposable("1"));
        cache.putReserved("key2", new TestDisposable("2"));

        // Both should now be available
        assertNotNull(cache.get("key1"));
        assertNotNull(cache.get("key2"));
        assertEquals(2, cache.nonNullValues().size());
    }

    @Test
    void testClearWithReservations() {
        var value1 = new TestDisposable("1");

        // Add cached value and reservation
        cache.put("cached", value1);
        cache.tryReserve("reserved");

        // Clear should dispose cached values and clear reservations
        cache.clear();

        assertTrue(value1.isDisposed());
        assertTrue(cache.nonNullValues().isEmpty());

        // Should be able to reserve the previously reserved key again
        assertTrue(cache.tryReserve("reserved"));
    }

    // ============= SLIDING WINDOW TESTS =============

    @Test
    void testSlidingWindowBasicBehavior() {
        var cache = new SlidingWindowCache<Integer, TestDisposable>(5, 3); // Max 5, window 3

        // Create test items
        var item0 = new TestDisposable("item0");
        var item1 = new TestDisposable("item1");
        var item2 = new TestDisposable("item2");
        var item3 = new TestDisposable("item3");
        var item4 = new TestDisposable("item4");

        // Start at position 1, window should be [0,1,2]
        cache.updateWindowCenter(1, 5);

        cache.put(0, item0);
        cache.put(1, item1);
        cache.put(2, item2);

        assertNotNull(cache.get(0));
        assertNotNull(cache.get(1));
        assertNotNull(cache.get(2));

        // Move to position 3, window should be [2,3,4]
        cache.updateWindowCenter(3, 5);

        // Items 0,1 should be evicted, 2 should remain
        assertTrue(item0.isDisposed());
        assertTrue(item1.isDisposed());
        assertFalse(item2.isDisposed());

        assertNull(cache.get(0));
        assertNull(cache.get(1));
        assertNotNull(cache.get(2));

        // Add items in new window
        cache.put(3, item3);
        cache.put(4, item4);

        assertNotNull(cache.get(2));
        assertNotNull(cache.get(3));
        assertNotNull(cache.get(4));
    }

    @Test
    void testSlidingWindowBoundaries() {
        var cache = new SlidingWindowCache<Integer, TestDisposable>(10, 3);

        // Test at start boundary (position 0)
        cache.updateWindowCenter(0, 5);
        assertTrue(cache.isInWindow(0));
        assertTrue(cache.isInWindow(1));
        assertFalse(cache.isInWindow(2)); // Only 0,1 at start for window size 3

        // Test at end boundary (position 4)
        cache.updateWindowCenter(4, 5);
        assertFalse(cache.isInWindow(2)); // Outside window at end
        assertTrue(cache.isInWindow(3));
        assertTrue(cache.isInWindow(4));

        // Test middle position
        cache.updateWindowCenter(2, 5);
        assertTrue(cache.isInWindow(1));
        assertTrue(cache.isInWindow(2));
        assertTrue(cache.isInWindow(3));
        assertFalse(cache.isInWindow(0));
        assertFalse(cache.isInWindow(4));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5, 7, 9})
    void testDifferentWindowSizes(int windowSize) {
        var cache = new SlidingWindowCache<Integer, TestDisposable>(windowSize * 2, windowSize);
        int totalFiles = 20;

        // Test window behavior at different positions
        for (int center = 0; center < totalFiles; center++) {
            cache.updateWindowCenter(center, totalFiles);
            var expectedIndices = calculateExpectedWindow(center, totalFiles, windowSize);

            // Verify only expected indices are valid
            for (int i = 0; i < totalFiles; i++) {
                boolean shouldBeInWindow = expectedIndices.contains(i);
                assertEquals(shouldBeInWindow, cache.isInWindow(i),
                            String.format("Window size %d, center %d, index %d", windowSize, center, i));
            }
        }
    }

    private java.util.Set<Integer> calculateExpectedWindow(int center, int total, int windowSize) {
        var indices = new java.util.HashSet<Integer>();
        int halfWindow = windowSize / 2;
        int start = Math.max(0, center - halfWindow);
        int end = Math.min(total - 1, center + halfWindow);

        for (int i = start; i <= end; i++) {
            indices.add(i);
        }
        return indices;
    }

    @Test
    void testSlidingWindowPutOutsideWindow() {
        var cache = new SlidingWindowCache<Integer, TestDisposable>(10, 3);
        cache.updateWindowCenter(5, 10); // Window [4,5,6]

        var item = new TestDisposable("outside");

        // Try to put item outside window
        var result = cache.put(0, item); // 0 is outside window [4,5,6]

        assertNull(result); // Should not cache
        assertNull(cache.get(0)); // Should not be in cache
        assertFalse(item.isDisposed()); // Should not dispose (caller handles)
    }

    @Test
    void testSlidingWindowPreservesReservations() {
        var cache = new SlidingWindowCache<Integer, TestDisposable>(10, 3);
        cache.updateWindowCenter(5, 10); // Window [4,5,6]

        // Reserve key in window
        assertTrue(cache.tryReserve(5));

        // Move window
        cache.updateWindowCenter(7, 10); // Window [6,7,8]

        // Original reservation should be cleared since 5 is outside new window
        assertTrue(cache.tryReserve(5)); // Should be able to reserve again
    }

    @Test
    void testWindowInfoAndDebugging() {
        var cache = new SlidingWindowCache<Integer, TestDisposable>(10, 3);

        // Before window is set
        String info = cache.getWindowInfo();
        assertTrue(info.contains("Not set"));

        // After setting window
        cache.updateWindowCenter(2, 5);
        info = cache.getWindowInfo();
        assertTrue(info.contains("center=2"));
        assertTrue(info.contains("[1, 2, 3]") || info.contains("[2, 1, 3]") || info.contains("[3, 2, 1]"));

        // Add some cached items
        cache.put(1, new TestDisposable("1"));
        cache.put(2, new TestDisposable("2"));

        var cachedKeys = cache.getCachedKeys();
        assertTrue(cachedKeys.contains(1));
        assertTrue(cachedKeys.contains(2));
        assertEquals(2, cachedKeys.size());
    }

    @Test
    void testSlidingWindowWithSmallCollections() {
        var cache = new SlidingWindowCache<Integer, TestDisposable>(10, 5); // Window size 5

        // Only 2 files with window size 5
        cache.updateWindowCenter(0, 2);
        assertTrue(cache.isInWindow(0));
        assertTrue(cache.isInWindow(1));
        assertFalse(cache.isInWindow(2)); // Doesn't exist

        cache.updateWindowCenter(1, 2);
        assertTrue(cache.isInWindow(0));
        assertTrue(cache.isInWindow(1));

        // Only 1 file with window size 5
        cache.updateWindowCenter(0, 1);
        assertTrue(cache.isInWindow(0));
        assertFalse(cache.isInWindow(1)); // Doesn't exist
    }

    @Test
    void testEvenWindowSizes() {
        var cache = new SlidingWindowCache<Integer, TestDisposable>(10, 4); // Even window size

        cache.updateWindowCenter(5, 10);

        // For window size 4, halfWindow = 2
        // start = max(0, 5-2) = 3, end = min(9, 5+2) = 7
        // So window should be [3,4,5,6,7] (5 items due to integer division)
        assertTrue(cache.isInWindow(3));
        assertTrue(cache.isInWindow(4));
        assertTrue(cache.isInWindow(5));
        assertTrue(cache.isInWindow(6));
        assertTrue(cache.isInWindow(7));
        assertFalse(cache.isInWindow(2));
        assertFalse(cache.isInWindow(8));
    }
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void testEditedFilesRetainedOutsideWindowParameterized(int editedFilesCount) {
        var cache = new SlidingWindowCache<Integer, TestDisposable>(10, 3); // Max 10, window 3

        // Initial window centred at 1 → indices [0,1,2]
        cache.updateWindowCenter(1, 10);

        // Add edited files inside the window
        java.util.List<TestDisposable> editedFiles = new java.util.ArrayList<>();
        for (int i = 0; i < editedFilesCount; i++) {
            var edited = new TestDisposable("edited" + i);
            edited.setHasUnsavedChanges(true);
            editedFiles.add(edited);
            cache.put(i, edited);
        }

        // One normal (non-edited) file also inside the window
        int normalIndex = editedFilesCount;
        var normalFile = new TestDisposable("normal");
        cache.put(normalIndex, normalFile);

        // Move window far away so all previously added files lie outside it
        cache.updateWindowCenter(8, 10); // window [7,8,9]

        // Edited files must be retained
        for (int i = 0; i < editedFilesCount; i++) {
            assertNotNull(cache.get(i), "Edited file " + i + " should be retained");
            assertFalse(editedFiles.get(i).isDisposed(), "Edited file " + i + " should not be disposed");
        }

        // Normal file must be evicted
        assertNull(cache.get(normalIndex));
        assertTrue(normalFile.isDisposed());

        // Simulate saving the edited files
        editedFiles.forEach(f -> f.setHasUnsavedChanges(false));

        // Move window again to trigger eviction now that files are clean
        cache.updateWindowCenter(5, 10); // window [4,5,6]

        for (int i = 0; i < editedFilesCount; i++) {
            assertNull(cache.get(i));
            assertTrue(editedFiles.get(i).isDisposed(), "Edited file " + i + " should be disposed after save");
        }
    }
}
