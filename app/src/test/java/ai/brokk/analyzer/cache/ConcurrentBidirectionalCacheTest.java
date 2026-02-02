package ai.brokk.analyzer.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConcurrentBidirectionalCacheTest {

    @Test
    void testBasicForwardAndReverse() {
        // A cache where forward is K -> List<String> and reverse is String -> Set<String>
        ConcurrentBidirectionalCache<String, List<String>, Set<String>> cache = new ConcurrentBidirectionalCache<>(
                (self, values) -> {
                    // This populator would be used to index which keys contain which strings
                },
                Collections::emptyList);

        // Manual population for test via logic similar to what reversePopulator would do
        cache.computeForwardIfAbsent("key1", k -> {
            List<String> vals = List.of("a", "b");
            for (String v : vals) {
                cache.updateReverse(k, existing -> {
                    Set<String> set = existing != null ? existing : new HashSet<>();
                    set.add(v);
                    return set;
                });
            }
            return vals;
        });

        assertEquals(List.of("a", "b"), cache.getForward("key1"));
        assertEquals(Set.of("a", "b"), cache.getReverse("key1"));
    }

    @Test
    void testRecursionGuard() {
        AtomicInteger computeCount = new AtomicInteger(0);
        ConcurrentBidirectionalCache<String, String, String> cache =
                new ConcurrentBidirectionalCache<>((self, v) -> {}, () -> "CIRCULAR");

        String result = cache.computeForwardIfAbsent("A", k -> {
            computeCount.incrementAndGet();
            // Try to compute A again while computing A
            return cache.computeForwardIfAbsent("A", k2 -> "SHOULD_NOT_HAPPEN");
        });

        assertEquals("CIRCULAR", result);
        assertEquals(1, computeCount.get(), "Computer should only be called once before guard triggers");
    }

    @Test
    void testReversePopulator() {
        // Use a key that can be derived or captured to verify populator behavior
        String targetKey = "hello";
        // Reverse cache tracks how many characters are in the forward string
        ConcurrentBidirectionalCache<String, String, Integer> cache = new ConcurrentBidirectionalCache<>(
                (self, v) -> self.updateReverse(targetKey, existing -> v.length()), () -> "");

        cache.computeForwardIfAbsent(targetKey, k -> "world");

        assertEquals("world", cache.getForward(targetKey));
        assertEquals(5, cache.getReverse(targetKey));
    }
}
