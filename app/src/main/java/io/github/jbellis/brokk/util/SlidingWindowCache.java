package io.github.jbellis.brokk.util;

import org.jetbrains.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe sliding window cache implementation with proper synchronization and disposal callbacks.
 * Uses ReentrantReadWriteLock for fine-grained concurrency control.
 *
 * Features:
 * - Sliding window eviction: maintains only items within a configurable position-based window
 * - Deferred disposal pattern: dispose() calls happen outside of locks
 * - Reservation tracking: eliminates temporary null storage for better type safety
 * - Non-blocking disposal: other cache operations can proceed during disposal
 * - LRU fallback: applies LRU eviction within the sliding window when at capacity
 *
 * @param <K> the key type
 * @param <V> the value type, must implement Disposable interface
 */
public class SlidingWindowCache<K, V extends SlidingWindowCache.Disposable> {
    private static final Logger logger = LogManager.getLogger(SlidingWindowCache.class);

    /**
     * Interface for objects that can be disposed when evicted from cache.
     */
    public interface Disposable {
        void dispose();

        /**
         * Check if this object has unsaved changes that should prevent eviction.
         * Default implementation returns false for backward compatibility.
         *
         * @return true if the object has unsaved changes and should not be evicted
         */
        default boolean hasUnsavedChanges() {
            return false;
        }
    }

    private final int maxSize;
    private final int windowSize;
    private final LinkedHashMap<K, V> cache;
    private final Set<K> reservedKeys = new HashSet<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    // Sliding window state
    private volatile int currentWindowCenter = -1;
    private volatile int totalItems = -1;

    public SlidingWindowCache(int maxSize) {
        this(maxSize, maxSize); // Default: windowSize = maxSize (LRU behavior)
    }

    /**
     * Constructor with explicit window size for sliding window behavior
     * @param maxSize Maximum cache size (should be >= windowSize)
     * @param windowSize Size of sliding window (e.g., 3 for current + 2 adjacent)
     */
    public SlidingWindowCache(int maxSize, int windowSize) {
        this.maxSize = maxSize;
        this.windowSize = windowSize;
        this.cache = new LinkedHashMap<>(maxSize + 1, 0.75f, true);
    }

    @Nullable
    public V get(K key) {
        readLock.lock();
        try {
            // Return null if key is reserved but not yet loaded
            if (reservedKeys.contains(key)) {
                return null;
            }
            return cache.get(key);
        } finally {
            readLock.unlock();
        }
    }

    @Nullable
    public V put(K key, V value) {
        // Check if key is within current window
        if (!isInWindow(key)) {
            // Don't cache, but don't dispose value either - caller handles it
            return null;
        }

        // Collect items to dispose outside of lock
        var toDispose = new ArrayList<V>();
        @Nullable V previousValue;

        writeLock.lock();
        try {
            // Window-aware eviction: remove items outside window first
            evictOutsideWindow(toDispose);

            // Then apply normal LRU eviction if still needed
            if (cache.size() >= maxSize && !cache.containsKey(key) && !reservedKeys.contains(key)) {
                var eldest = cache.entrySet().iterator().next();
                var evictedValue = cache.remove(eldest.getKey());
                if (evictedValue != null) {
                    toDispose.add(evictedValue);
                }
            }

            // Remove from reserved keys if this was a reservation
            reservedKeys.remove(key);

            previousValue = cache.put(key, value);
        } finally {
            writeLock.unlock();
        }

        // Dispose outside of lock to avoid blocking other operations
        disposeDeferred(toDispose);

        return previousValue;
    }

    public boolean containsValue(V value) {
        readLock.lock();
        try {
            return cache.containsValue(value);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Atomic check-and-reserve operation to prevent double-loading.
     * Returns true if the key was not present and has been reserved.
     * Returns false if the key was already present (cached value exists) or already reserved.
     */
    public boolean tryReserve(K key) {
        writeLock.lock();
        try {
            if (cache.containsKey(key) || reservedKeys.contains(key)) {
                return false; // Already cached or reserved
            }
            // Reserve by adding to reservation set
            reservedKeys.add(key);
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Replaces a reserved entry with the actual value.
     * Should only be called after successful tryReserve().
     */
    @Nullable
    public V putReserved(K key, V value) {
        writeLock.lock();
        try {
            // Ensure we're replacing a reserved entry
            assert reservedKeys.contains(key) : "putReserved called on non-reserved key";
            reservedKeys.remove(key);
            return cache.put(key, value);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Removes a reserved entry (used when loading fails).
     */
    public void removeReserved(K key) {
        writeLock.lock();
        try {
            reservedKeys.remove(key);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Returns all cached values, excluding reserved (not yet loaded) entries.
     */
    public java.util.Collection<V> nonNullValues() {
        readLock.lock();
        try {
            // Since we no longer store nulls, all cache values are non-null
            return new ArrayList<>(cache.values());
        } finally {
            readLock.unlock();
        }
    }

    public void clear() {
        // Collect items to dispose outside of lock
        var toDispose = new ArrayList<V>();

        writeLock.lock();
        try {
            // Collect all values for disposal
            toDispose.addAll(cache.values());

            // Clear both cache and reservations
            cache.clear();
            reservedKeys.clear();
        } finally {
            writeLock.unlock();
        }

        // Dispose outside of lock to avoid blocking other operations
        disposeDeferred(toDispose);
    }

    /**
     * Update the sliding window center position.
     * This will trigger eviction of items outside the window.
     *
     * @param centerIndex The center position of the window
     * @param totalItemCount Total number of items in the collection
     */
    public void updateWindowCenter(int centerIndex, int totalItemCount) {
        if (centerIndex == currentWindowCenter && totalItemCount == totalItems) {
            return; // No change needed
        }

        var toDispose = new ArrayList<V>();

        writeLock.lock();
        try {
            this.currentWindowCenter = centerIndex;
            this.totalItems = totalItemCount;

            // Centralised eviction logic
            evictOutsideWindow(toDispose);

            // Clear reservations outside the new window
            var validIndices = calculateWindowIndices(centerIndex, totalItemCount);
            var reservationsToRemove = new ArrayList<K>();
            for (K key : reservedKeys) {
                if (key instanceof Integer intKey && !validIndices.contains(intKey)) {
                    reservationsToRemove.add(key);
                }
            }
            reservationsToRemove.forEach(reservedKeys::remove);
        } finally {
            writeLock.unlock();
        }

        disposeDeferred(toDispose);
    }

    /**
     * Calculate valid indices for sliding window
     */
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

    /**
     * Check if a key is within the current sliding window
     */
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

    /**
     * Get all currently cached keys (thread-safe)
     */
    public Set<K> getCachedKeys() {
        readLock.lock();
        try {
            return new HashSet<>(cache.keySet());
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Get current window information for debugging
     */
    public String getWindowInfo() {
        readLock.lock();
        try {
            var cachedKeys = new ArrayList<>(cache.keySet());
            if (currentWindowCenter == -1) {
                return "Window: Not set, cached keys: " + cachedKeys;
            }
            var validIndices = calculateWindowIndices(currentWindowCenter, totalItems);
            return String.format("Window: center=%d, valid=%s, cached=%s",
                                 currentWindowCenter, validIndices, cachedKeys);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Evict items that are outside the current sliding window.
     * Items with unsaved changes are retained even if outside the window.
     */
    private void evictOutsideWindow(List<V> toDispose) {
        if (currentWindowCenter == -1 || totalItems == -1) {
            return; // No window constraints
        }

        var validIndices = calculateWindowIndices(currentWindowCenter, totalItems);
        var keysToRemove = new ArrayList<K>();
        var retainedKeys = new ArrayList<K>();

        for (var entry : cache.entrySet()) {
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

        for (K key : keysToRemove) {
            cache.remove(key);
        }

        // Log warning if we're retaining files outside the window
        if (!retainedKeys.isEmpty()) {
            logger.warn("Memory usage increased: retaining {} edited files outside sliding window",
                       retainedKeys.size());
        }
    }

    /**
     * Helper method to dispose items outside of locks to prevent blocking.
     * This allows other cache operations to proceed while disposal is happening.
     */
    private void disposeDeferred(java.util.Collection<V> items) {
        for (var item : items) {
            try {
                item.dispose();
            } catch (Exception e) {
                logger.warn("Exception during disposal of cache item", e);
            }
        }
    }
}
