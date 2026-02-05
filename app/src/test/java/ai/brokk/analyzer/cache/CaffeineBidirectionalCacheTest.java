package ai.brokk.analyzer.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CaffeineBidirectionalCacheTest {

    @Test
    void testPutAllForward() {
        CaffeineBidirectionalCache<String, String, String> source =
                new CaffeineBidirectionalCache<>(100, (self, v) -> {}, () -> "");
        source.computeForwardIfAbsent("key1", k -> "val1");
        source.computeForwardIfAbsent("key2", k -> "val2");

        CaffeineBidirectionalCache<String, String, String> target =
                new CaffeineBidirectionalCache<>(100, (self, v) -> {}, () -> "");

        target.putAllForward(source);

        assertEquals("val1", target.getForward("key1"));
        assertEquals("val2", target.getForward("key2"));
        // Reverse should be null as it is populated lazily by the populator during computation
        assertNull(target.getReverse("key1"));
    }

    @Test
    void testPutAllForwardInvalidatesStaleReverse() {
        CaffeineBidirectionalCache<String, String, String> source =
                new CaffeineBidirectionalCache<>(100, (self, v) -> {}, () -> "");
        source.computeForwardIfAbsent("key1", k -> "new_val");

        CaffeineBidirectionalCache<String, String, String> target =
                new CaffeineBidirectionalCache<>(100, (self, v) -> {}, () -> "");

        // 1. Target has existing stale reverse entry
        target.updateReverse("key1", existing -> "stale_reverse");
        assertEquals("stale_reverse", target.getReverse("key1"));

        // 2. Call putAllForward
        target.putAllForward(source);

        // 3. Assert reverse is cleared and forward is updated
        assertEquals("new_val", target.getForward("key1"));
        assertNull(target.getReverse("key1"), "Reverse cache should have been invalidated to prevent stale data");
    }

    @Test
    void testBasicForwardAndReverse() {
        CaffeineBidirectionalCache<String, List<String>, Set<String>> cache = new CaffeineBidirectionalCache<>(
                100,
                (self, values) -> {
                    // No-op for this test
                },
                Collections::emptyList);

        // Manual population simulation
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
        CaffeineBidirectionalCache<String, String, String> cache =
                new CaffeineBidirectionalCache<>(100, (self, v) -> {}, () -> "CIRCULAR");

        String result = cache.computeForwardIfAbsent("A", k -> {
            computeCount.incrementAndGet();
            return cache.computeForwardIfAbsent("A", k2 -> "SHOULD_NOT_HAPPEN");
        });

        assertEquals("CIRCULAR", result);
        assertEquals(1, computeCount.get(), "Computer should only be called once");
    }

    @Test
    void testReversePopulator() {
        String targetKey = "hello";
        CaffeineBidirectionalCache<String, String, Integer> cache = new CaffeineBidirectionalCache<>(
                100, (self, v) -> self.updateReverse(targetKey, existing -> v.length()), () -> "");

        cache.computeForwardIfAbsent(targetKey, k -> "world");

        assertEquals("world", cache.getForward(targetKey));
        assertEquals(5, cache.getReverse(targetKey));
    }

    @Test
    void testEviction() {
        CaffeineBidirectionalCache<Integer, String, String> cache =
                new CaffeineBidirectionalCache<>(2, (self, v) -> {}, () -> "");

        cache.computeForwardIfAbsent(1, k -> "one");
        cache.computeForwardIfAbsent(2, k -> "two");
        cache.computeForwardIfAbsent(3, k -> "three");

        cache.cleanUp(); // Trigger maintenance

        int count = 0;
        if (cache.getForward(1) != null) count++;
        if (cache.getForward(2) != null) count++;
        if (cache.getForward(3) != null) count++;

        assertTrue(count <= 2, "Should have evicted down to size 2, found " + count);
    }
}
