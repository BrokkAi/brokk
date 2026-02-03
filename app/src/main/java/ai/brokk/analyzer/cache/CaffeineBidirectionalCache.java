package ai.brokk.analyzer.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caffeine-backed implementation of BidirectionalCache with recursion guarding.
 */
public final class CaffeineBidirectionalCache<K, V, RV> implements BidirectionalCache<K, V, RV> {
    private static final Logger log = LoggerFactory.getLogger(CaffeineBidirectionalCache.class);

    private final Cache<K, V> forwardCache;
    private final Cache<K, RV> reverseCache;
    private final ThreadLocal<Set<K>> recursionGuard = ThreadLocal.withInitial(HashSet::new);

    private final BiConsumer<BidirectionalCache<K, V, RV>, V> reversePopulator;
    private final Supplier<V> emptyValueSupplier;

    public CaffeineBidirectionalCache(
            long maxSize,
            BiConsumer<BidirectionalCache<K, V, RV>, V> reversePopulator,
            Supplier<V> emptyValueSupplier) {
        this.forwardCache = Caffeine.newBuilder().maximumSize(maxSize).build();
        this.reverseCache = Caffeine.newBuilder().maximumSize(maxSize).build();
        this.reversePopulator = reversePopulator;
        this.emptyValueSupplier = emptyValueSupplier;
    }

    @Override
    @Nullable
    public V getForward(K key) {
        return forwardCache.getIfPresent(key);
    }

    @Override
    @Nullable
    public RV getReverse(K key) {
        return reverseCache.getIfPresent(key);
    }

    @Override
    public void putAllForward(BidirectionalCache<K, V, RV> source) {
        forwardCache.invalidateAll();
        reverseCache.invalidateAll();
        source.forEachForward(forwardCache::put);
    }

    @Override
    public V computeForwardIfAbsent(K key, Function<K, V> computer) {
        V existing = forwardCache.getIfPresent(key);
        if (existing != null) {
            return existing;
        }

        var visiting = recursionGuard.get();
        if (!visiting.add(key)) {
            log.trace("Circular dependency detected for key: {}", key);
            return emptyValueSupplier.get();
        }

        try {
            return forwardCache.get(key, k -> {
                V value = computer.apply(k);
                reversePopulator.accept(this, value);
                return value;
            });
        } finally {
            visiting.remove(key);
        }
    }

    @Override
    public void updateReverse(K key, Function<@Nullable RV, RV> updater) {
        reverseCache.asMap().compute(key, (k, existing) -> updater.apply(existing));
    }

    @Override
    public boolean isEmpty() {
        return forwardCache.estimatedSize() == 0;
    }

    @Override
    public void forEachForward(BiConsumer<? super K, ? super V> action) {
        forwardCache.asMap().forEach(action);
    }

    @Override
    public void forEachReverse(BiConsumer<? super K, ? super RV> action) {
        reverseCache.asMap().forEach(action);
    }

    @Override
    public void cleanUp() {
        forwardCache.cleanUp();
        reverseCache.cleanUp();
    }
}
