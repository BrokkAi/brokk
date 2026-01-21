package ai.brokk.util;

import java.util.Optional;
import java.util.function.Supplier;

public interface IStringDiskCache extends AutoCloseable {
    Optional<String> get(String key);

    void put(String key, String value);

    /**
     * Returns the cached value for the key, or computes and caches it if absent.
     *
     * @param key the cache key (must match [a-z0-9_-]{1,64})
     * @param computer supplier for the value if not in cache
     * @return the cached or computed value
     */
    default String computeIfAbsent(String key, Supplier<String> computer) {
        return get(key).orElseGet(() -> {
            String value = computer.get();
            put(key, value);
            return value;
        });
    }

    /**
     * Returns the cached value for the key, or computes and caches it if absent, allowing interruption.
     *
     * @param key the cache key
     * @param computer supplier for the value if not in cache
     * @return the cached or computed value
     * @throws InterruptedException if the computation is interrupted
     */
    default String computeIfAbsentInterruptibly(String key, InterruptibleSupplier<String> computer)
            throws InterruptedException {
        var cached = get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        String value = computer.get();
        put(key, value);
        return value;
    }

    public static class NoopDiskCache implements IStringDiskCache {
        @Override
        public Optional<String> get(String key) {
            return Optional.empty();
        }

        @Override
        public void put(String key, String value) {}

        @Override
        public String computeIfAbsent(String key, Supplier<String> computer) {
            return computer.get();
        }

        @Override
        public String computeIfAbsentInterruptibly(String key, InterruptibleSupplier<String> computer)
                throws InterruptedException {
            return computer.get();
        }

        @Override
        public void close() throws Exception {}
    }
}
