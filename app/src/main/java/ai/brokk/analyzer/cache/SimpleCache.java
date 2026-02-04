package ai.brokk.analyzer.cache;

import java.util.function.BiConsumer;
import org.jetbrains.annotations.Nullable;

/**
 * A simple key-value cache interface.
 */
public interface SimpleCache<K, V> {
    @Nullable
    V get(K key);

    void put(K key, V value);

    /**
     * Copies all entries from the source cache into this cache.
     */
    void putAll(SimpleCache<K, V> source);

    boolean isEmpty();

    void forEach(BiConsumer<? super K, ? super V> action);

    /**
     * Performs any pending maintenance operations needed by the cache.
     */
    void cleanUp();
}
