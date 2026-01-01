package ai.brokk.util;

import com.jakewharton.disklrucache.DiskLruCache;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

/**
 * A string-specialized wrapper around {@link DiskLruCache}.
 */
@NullMarked
public final class StringDiskCache implements AutoCloseable {
    private static final Logger logger = LogManager.getLogger(StringDiskCache.class);
    private final DiskLruCache cache;

    public StringDiskCache(DiskLruCache cache) {
        this.cache = Objects.requireNonNull(cache);
    }

    /**
     * Returns the cached value for the key, or empty if not present.
     *
     * @param key the cache key (must match [a-z0-9_-]{1,64})
     * @return the cached value, or empty if not in cache
     */
    public Optional<String> get(String key) {
        try (DiskLruCache.Snapshot snapshot = cache.get(key)) {
            if (snapshot != null) {
                return Optional.of(snapshot.getString(0));
            }
        } catch (IOException e) {
            logger.warn("Failed to read from disk cache for key {}: {}", key, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Stores a value in the cache.
     *
     * @param key   the cache key (must match [a-z0-9_-]{1,64})
     * @param value the value to store
     */
    public void put(String key, String value) {
        DiskLruCache.Editor editor = null;
        try {
            editor = cache.edit(key);
            if (editor != null) {
                editor.set(0, value);
                editor.commit();
            }
        } catch (IOException e) {
            logger.warn("Failed to write to disk cache for key {}: {}", key, e.getMessage());
            if (editor != null) {
                try {
                    editor.abort();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Returns the cached value for the key, or computes and caches it if absent.
     *
     * @param key the cache key (must match [a-z0-9_-]{1,64})
     * @param computer supplier for the value if not in cache
     * @return the cached or computed value
     */
    public String computeIfAbsent(String key, Supplier<String> computer) {
        return get(key).orElseGet(() -> {
            String value = computer.get();
            put(key, value);
            return value;
        });
    }

    @Override
    public void close() {
        try {
            cache.close();
        } catch (IOException e) {
            logger.warn("Error closing disk cache: {}", e.getMessage());
        }
    }
}
