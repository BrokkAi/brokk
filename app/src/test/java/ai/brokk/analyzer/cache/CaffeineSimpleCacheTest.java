package ai.brokk.analyzer.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaffeineSimpleCacheTest {

    @Test
    void testBasicOperations() {
        SimpleCache<String, Integer> cache = new CaffeineSimpleCache<>(10);

        assertTrue(cache.isEmpty());
        assertNull(cache.get("key1"));

        cache.put("key1", 1);
        assertFalse(cache.isEmpty());
        assertEquals(1, cache.get("key1"));

        cache.put("key2", 2);
        assertEquals(2, cache.get("key2"));
    }

    @Test
    void testPutAll() {
        SimpleCache<String, Integer> source = new CaffeineSimpleCache<>(10);
        source.put("key1", 1);
        source.put("key2", 2);

        SimpleCache<String, Integer> target = new CaffeineSimpleCache<>(10);
        target.putAll(source);

        assertEquals(1, target.get("key1"));
        assertEquals(2, target.get("key2"));
    }

    @Test
    void testForEach() {
        SimpleCache<String, Integer> cache = new CaffeineSimpleCache<>(10);
        cache.put("a", 1);
        cache.put("b", 2);

        Map<String, Integer> results = new HashMap<>();
        cache.forEach(results::put);

        assertEquals(2, results.size());
        assertEquals(1, results.get("a"));
        assertEquals(2, results.get("b"));
    }

    @Test
    void testMaxSizeEviction() {
        SimpleCache<Integer, Integer> cache = new CaffeineSimpleCache<>(2);

        cache.put(1, 1);
        cache.put(2, 2);
        cache.put(3, 3); // Should trigger eviction of one element

        // Caffeine eviction is eventual; force maintenance to ensure eviction happens before check.
        cache.cleanUp();

        int count = 0;
        if (cache.get(1) != null) count++;
        if (cache.get(2) != null) count++;
        if (cache.get(3) != null) count++;

        assertTrue(count <= 2, "Cache size should not exceed max size of 2, but was " + count);
    }
}
