package io.github.jbellis.brokk.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Thread-safe sliding window cache implementation using ConcurrentHashMap and ConcurrentLinkedDeque.
 *
 * <p>Features: - Sliding window eviction: maintains only items within a configurable position-based window - Deferred
 * disposal pattern: dispose() calls happen asynchronously - Reservation tracking: eliminates temporary null storage for
 * better type safety - Lock-free operations: uses concurrent data structures for better performance - LRU fallback:
 * applies LRU eviction within the sliding window when at capacity
 *
 * @param <K> the key type
 * @param <V> the value type, must implement Disposable interface
 */
public class SlidingWindowCache<K, V extends SlidingWindowCache.Disposable> {
    private static final Logger logger = LogManager.getLogger(SlidingWindowCache.class);

    /** Interface for objects that can be disposed when evicted from cache. */
    public interface Disposable {
        void dispose();

        /**
         * Check if this object has unsaved changes that should prevent eviction. Default implementation returns false
         * for backward compatibility.
         *
         * @return true if the object has unsaved changes and should not be evicted
         */
        default boolean hasUnsavedChanges() {
            return false;
        }
    }

    private final int maxSize;
    private final int windowSize;
    private final ConcurrentHashMap<K, V> cache;
    private final ConcurrentLinkedDeque<K> accessOrder;
    private final ConcurrentHashMap<K, Boolean> reservedKeys;
    private final AtomicInteger currentSize;

    // Sliding window state
    private volatile int currentWindowCenter = -1;
    private volatile int totalItems = -1;

    public SlidingWindowCache(int maxSize) {
        this(maxSize, maxSize); // Default: windowSize = maxSize (LRU behavior)
    }

    /**
     * Constructor with explicit window size for sliding window behavior
     *
     * @param maxSize Maximum cache size (should be >= windowSize)
     * @param windowSize Size of sliding window (e.g., 3 for current + 2 adjacent)
     */
    public SlidingWindowCache(int maxSize, int windowSize) {
        this.maxSize = maxSize;
        this.windowSize = windowSize;
        this.cache = new ConcurrentHashMap<>(maxSize + 1);
        this.accessOrder = new ConcurrentLinkedDeque<>();
        this.reservedKeys = new ConcurrentHashMap<>();
        this.currentSize = new AtomicInteger(0);
    }

    @Nullable
    public V get(K key) {
        // Return null if key is reserved but not yet loaded
        if (reservedKeys.containsKey(key)) {
            return null;
        }

        V value = cache.get(key);
        if (value != null) {
            // Update access order - remove and re-add to end
            accessOrder.remove(key);
            accessOrder.offer(key);
        }
        return value;
    }

    @Nullable
    public V put(K key, V value) {
        // Check if key is within current window
        if (!isInWindow(key)) {
            // Don't cache, but don't dispose value either - caller handles it
            return null;
        }

        // Collect items to dispose
        var toDispose = new ArrayList<V>();
        V previousValue;

        // Synchronize the critical section to prevent size inconsistencies
        synchronized (this) {
            // Window-aware eviction: remove items outside window first
            evictOutsideWindow(toDispose);

            // Perform LRU eviction if needed - loop until under capacity
            while (currentSize.get() >= maxSize && !cache.containsKey(key) && !reservedKeys.containsKey(key)) {
                K eldestKey = accessOrder.poll();
                if (eldestKey == null) {
                    break; // No more items to evict
                }

                V evictedValue = cache.remove(eldestKey);
                if (evictedValue != null) {
                    toDispose.add(evictedValue);
                    currentSize.decrementAndGet();
                }
            }

            // Remove from reserved keys if this was a reservation
            reservedKeys.remove(key);

            // Add/update the value
            previousValue = cache.put(key, value);

            // Update access order and size
            accessOrder.remove(key); // Remove if already present
            accessOrder.offer(key); // Add to end (most recent)

            if (previousValue == null) {
                currentSize.incrementAndGet();
            } else {
                toDispose.add(previousValue);
            }
        }

        // Dispose outside of synchronized block to avoid blocking other operations
        disposeDeferred(toDispose);

        return previousValue;
    }

    public boolean containsValue(V value) {
        return cache.containsValue(value);
    }

    /**
     * Atomic check-and-reserve operation to prevent double-loading. Returns true if the key was not present and has
     * been reserved. Returns false if the key was already present (cached value exists) or already reserved.
     */
    public boolean tryReserve(K key) {
        if (cache.containsKey(key) || reservedKeys.containsKey(key)) {
            return false; // Already cached or reserved
        }
        // Atomic check-and-reserve operation
        return reservedKeys.putIfAbsent(key, true) == null;
    }

    /** Replaces a reserved entry with the actual value. Should only be called after successful tryReserve(). */
    @Nullable
    public V putReserved(K key, V value) {
        // Ensure we're replacing a reserved entry
        assert reservedKeys.containsKey(key) : "putReserved called on non-reserved key";
        reservedKeys.remove(key);

        V previousValue = cache.put(key, value);

        // Update access order and size
        accessOrder.remove(key); // Remove if already present
        accessOrder.offer(key); // Add to end (most recent)

        if (previousValue == null) {
            currentSize.incrementAndGet();
        }

        return previousValue;
    }

    /** Removes a reserved entry (used when loading fails). */
    public void removeReserved(K key) {
        reservedKeys.remove(key);
    }

    /** Returns all cached values, excluding reserved (not yet loaded) entries. */
    public Collection<V> nonNullValues() {
        // Since we no longer store nulls, all cache values are non-null
        return new ArrayList<>(cache.values());
    }

    public void clear() {
        // Collect all values for disposal
        var toDispose = new ArrayList<>(cache.values());

        // Clear all data structures
        cache.clear();
        accessOrder.clear();
        reservedKeys.clear();
        currentSize.set(0);

        // Dispose outside of clearing to avoid blocking other operations
        disposeDeferred(toDispose);
    }

    /**
     * Update the sliding window center position. This will trigger eviction of items outside the window.
     *
     * @param centerIndex The center position of the window
     * @param totalItemCount Total number of items in the collection
     */
    public void updateWindowCenter(int centerIndex, int totalItemCount) {
        if (centerIndex == currentWindowCenter && totalItemCount == totalItems) {
            return; // No change needed
        }

        var toDispose = new ArrayList<V>();

        synchronized (this) {
            this.currentWindowCenter = centerIndex;
            this.totalItems = totalItemCount;

            // Centralised eviction logic
            evictOutsideWindow(toDispose);

            // Clear reservations outside the new window
            var validIndices = calculateWindowIndices(centerIndex, totalItemCount);
            var reservationsToRemove = new ArrayList<K>();
            for (K key : reservedKeys.keySet()) {
                if (key instanceof Integer intKey && !validIndices.contains(intKey)) {
                    reservationsToRemove.add(key);
                }
            }
            reservationsToRemove.forEach(reservedKeys::remove);
        }

        disposeDeferred(toDispose);
    }

    /** Calculate valid indices for sliding window */
    private Set<Integer> calculateWindowIndices(int center, int total) {
        var indices = new HashSet<Integer>();

        int halfWindow = windowSize / 2;
        int start = Math.max(0, center - halfWindow);
        int end = Math.min(total - 1, center + halfWindow);

        // Ensure we have exactly windowSize items (or less at boundaries)
        for (int i = start; i <= end; i++) {
            indices.add(i);
        }

        return indices;
    }

    /** Check if a key is within the current sliding window */
    public boolean isInWindow(K key) {
        if (currentWindowCenter == -1 || totalItems == -1) {
            return true; // No window set, allow all
        }

        if (key instanceof Integer intKey) {
            var validIndices = calculateWindowIndices(currentWindowCenter, totalItems);
            return validIndices.contains(intKey);
        }

        return true; // Non-integer keys always allowed
    }

    /** Get all currently cached keys (thread-safe) */
    public Set<K> getCachedKeys() {
        return new HashSet<>(cache.keySet());
    }

    /** Get current window information for debugging */
    public String getWindowInfo() {
        var cachedKeys = new ArrayList<>(cache.keySet());
        if (currentWindowCenter == -1) {
            return "Window: Not set, cached keys: " + cachedKeys;
        }
        var validIndices = calculateWindowIndices(currentWindowCenter, totalItems);
        return String.format("Window: center=%d, valid=%s, cached=%s", currentWindowCenter, validIndices, cachedKeys);
    }

    /**
     * Evict items that are outside the current sliding window. Items with unsaved changes are retained even if outside
     * the window.
     */
    private void evictOutsideWindow(List<V> toDispose) {
        if (currentWindowCenter == -1 || totalItems == -1) {
            return; // No window constraints
        }

        var validIndices = calculateWindowIndices(currentWindowCenter, totalItems);
        var keysToRemove = new ArrayList<K>();
        var retainedKeys = new ArrayList<K>();

        // Create a snapshot of cache entries to avoid concurrent modification
        var entries = new ArrayList<>(cache.entrySet());
        for (var entry : entries) {
            K key = entry.getKey();
            if (key instanceof Integer intKey && !validIndices.contains(intKey)) {
                // Check if the item has unsaved changes
                if (entry.getValue().hasUnsavedChanges()) {
                    retainedKeys.add(key);
                } else {
                    keysToRemove.add(key);
                    toDispose.add(entry.getValue());
                }
            }
        }

        // Remove evicted keys from cache and access order
        for (K key : keysToRemove) {
            if (cache.remove(key) != null) {
                accessOrder.remove(key);
                currentSize.decrementAndGet();
            }
        }

        // Log warning if we're retaining files outside the window
        if (!retainedKeys.isEmpty()) {
            logger.warn(
                    "Memory usage increased: retaining {} edited files outside sliding window", retainedKeys.size());
        }
    }

    /**
     * Helper method to dispose items outside of locks to prevent blocking. This allows other cache operations to
     * proceed while disposal is happening.
     */
    private void disposeDeferred(Collection<V> items) {
        for (var item : items) {
            try {
                item.dispose();
            } catch (Exception e) {
                logger.warn("Exception during disposal of cache item", e);
            }
        }
    }
}
