package ai.brokk.analyzer.cache;

import java.util.function.BiConsumer;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * A cache that maintains both forward and reverse lookups.
 *
 * @param <K> The key type.
 * @param <V> The forward value type.
 * @param <RV> The reverse value type (lookup by key).
 */
public interface BidirectionalCache<K, V, RV> {
    @Nullable
    V getForward(K key);

    @Nullable
    RV getReverse(K key);

    V computeForwardIfAbsent(K key, Function<K, V> computer);

    boolean isEmpty();

    void forEachForward(BiConsumer<? super K, ? super V> action);

    void forEachReverse(BiConsumer<? super K, ? super RV> action);
}
