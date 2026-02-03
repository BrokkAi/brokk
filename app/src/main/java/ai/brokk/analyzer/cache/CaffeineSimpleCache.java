package ai.brokk.analyzer.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.Nullable;

/**
 * Caffeine-backed implementation of SimpleCache.
 */
public final class CaffeineSimpleCache<K, V> implements SimpleCache<K, V> {
    private final Cache<K, V> cache;

    public CaffeineSimpleCache(long maxSize) {
        this.cache = Caffeine.newBuilder().maximumSize(maxSize).build();
    }

    @Override
    @Nullable
    public V get(K key) {
        return cache.getIfPresent(key);
    }

    @Override
    public void put(K key, V value) {
        cache.put(key, value);
    }

    @Override
    public void putAll(SimpleCache<K, V> source) {
        source.forEach(this::put);
    }

    @Override
    public boolean isEmpty() {
        return cache.estimatedSize() == 0;
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        cache.asMap().forEach(action);
    }

    @Override
    public void cleanUp() {
        cache.cleanUp();
    }
}
