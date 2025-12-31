package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jakewharton.disklrucache.DiskLruCache;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class StringDiskCacheTest {

    @Test
    public void testComputeIfAbsent(@TempDir Path tempDir) throws IOException {
        try (DiskLruCache dlc = DiskLruCache.open(tempDir.toFile(), 1, 1, 1024 * 1024)) {
            StringDiskCache cache = new StringDiskCache(dlc);
            AtomicInteger callCount = new AtomicInteger(0);

            String key = "test_key";
            String value = "hello_world";

            // First call: computes
            String result1 = cache.computeIfAbsent(key, () -> {
                callCount.incrementAndGet();
                return value;
            });

            assertEquals(value, result1);
            assertEquals(1, callCount.get());

            // Second call: from cache
            String result2 = cache.computeIfAbsent(key, () -> {
                callCount.incrementAndGet();
                return "different";
            });

            assertEquals(value, result2);
            assertEquals(1, callCount.get());
        }
    }
}
