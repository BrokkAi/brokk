package ai.brokk.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class RealPaths {
    private RealPaths() {}

    private static final Cache<Path, Path> REAL_PATH_CACHE =
            Caffeine.newBuilder().maximumSize(1024).build();

    /**
     * Helper to compare paths robustly by resolving real paths if possible.
     * Uses a cache to avoid redundant I/O for real path resolution.
     */
    /**
     * Helper to compare paths robustly by resolving real paths if possible.
     * Uses a cache to avoid redundant I/O for real path resolution.
     */
    public static boolean equals(Path a, Path b) {
        if (a.equals(b)) {
            return true;
        }
        return toRealPath(a).equals(toRealPath(b));
    }

    /**
     * Resolves the real path of the given path, using a cache to avoid redundant I/O.
     * If the path cannot be resolved (e.g., file does not exist), falls back to
     * an absolute, normalized path.
     */
    public static Path toRealPath(Path path) {
        return REAL_PATH_CACHE.get(path, p -> {
            try {
                return p.toRealPath();
            } catch (IOException e) {
                // If file doesn't exist or can't be resolved, use normalized path as fallback
                return p.toAbsolutePath().normalize();
            }
        });
    }
}
