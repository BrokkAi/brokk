package ai.brokk.analyzer.cache;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe implementation of BidirectionalCache with recursion guarding.
 */
public final class ConcurrentBidirectionalCache<K, V, RV> implements BidirectionalCache<K, V, RV> {
    private static final Logger log = LoggerFactory.getLogger(ConcurrentBidirectionalCache.class);

    private final ConcurrentHashMap<K, V> forwardCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<K, RV> reverseCache = new ConcurrentHashMap<>();
    private final ThreadLocal<Set<K>> recursionGuard = ThreadLocal.withInitial(HashSet::new);

    private final BiConsumer<ConcurrentBidirectionalCache<K, V, RV>, V> reversePopulator;
    private final Supplier<V> emptyValueSupplier;

    public ConcurrentBidirectionalCache(
            BiConsumer<ConcurrentBidirectionalCache<K, V, RV>, V> reversePopulator, Supplier<V> emptyValueSupplier) {
        this.reversePopulator = reversePopulator;
        this.emptyValueSupplier = emptyValueSupplier;
    }

    @Override
    @Nullable
    public V getForward(K key) {
        return forwardCache.get(key);
    }

    @Override
    @Nullable
    public RV getReverse(K key) {
        return reverseCache.get(key);
    }

    @Override
    public void updateReverse(K key, Function<@Nullable RV, RV> updater) {
        reverseCache.compute(key, (k, existing) -> updater.apply(existing));
    }

    @Override
    public V computeForwardIfAbsent(K key, Function<K, V> computer) {
        V existing = forwardCache.get(key);
        if (existing != null) {
            return existing;
        }

        var visiting = recursionGuard.get();
        if (!visiting.add(key)) {
            log.trace("Circular dependency detected for key: {}", key);
            return emptyValueSupplier.get();
        }

        try {
            return forwardCache.computeIfAbsent(key, k -> {
                V value = computer.apply(k);
                reversePopulator.accept(this, value);
                return value;
            });
        } finally {
            visiting.remove(key);
        }
    }

    @Override
    public boolean isEmpty() {
        return forwardCache.isEmpty();
    }

    @Override
    public void forEachForward(BiConsumer<? super K, ? super V> action) {
        forwardCache.forEach(action);
    }

    @Override
    public void forEachReverse(BiConsumer<? super K, ? super RV> action) {
        reverseCache.forEach(action);
    }

    @Override
    public void cleanUp() {
        // No-op for this implementation as it's primarily used for transient session state
    }
}
