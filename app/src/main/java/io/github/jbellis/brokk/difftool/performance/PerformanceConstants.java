package io.github.jbellis.brokk.difftool.performance;

/**
 * Constants for performance optimization configurations.
 * Centralizes all magic numbers and strings used in diff tool performance optimizations.
 */
public final class PerformanceConstants {

    // File size thresholds
    public static final long LARGE_FILE_THRESHOLD_BYTES = 1024 * 1024; // 1MB - responsive UI threshold
    public static final long MEDIUM_FILE_THRESHOLD_BYTES = 256 * 1024; // 256KB - caution threshold
    public static final long HUGE_FILE_THRESHOLD_BYTES = 5 * 1024 * 1024; // 5MB - memory warning threshold
    public static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB - absolute maximum to prevent OOM
    public static final long SINGLE_LINE_THRESHOLD_BYTES = 5 * 1024; // 5KB - threshold for single-line file optimizations

    // Performance degradation thresholds for line density
    public static final long REDUCED_SYNTAX_LINE_LENGTH_BYTES = 2 * 1024; // 2KB - reduce syntax features per line
    public static final long MINIMAL_SYNTAX_LINE_LENGTH_BYTES = 5 * 1024; // 5KB - minimal syntax features per line

    // Diff computation limits
    public static final long MAX_DIFF_LINE_LENGTH_BYTES = 50 * 1024; // 50KB - maximum line length for diff computation
     // Heuristic comparison
     public static final int HEURISTIC_PREFIX_BYTES = 4 * 1024; // Compare first 4KB when skipping diff

    // Timer delays (milliseconds)
    public static final int DEFAULT_UPDATE_TIMER_DELAY_MS = 400;
    public static final int LARGE_FILE_UPDATE_TIMER_DELAY_MS = 800;

    // Viewport optimization
    public static final long VIEWPORT_CACHE_VALIDITY_MS = 100;
    public static final int VIEWPORT_BUFFER_LINES = 20;

    // Performance monitoring
    public static final long SLOW_UPDATE_THRESHOLD_MS = 100;

    // Typing state management
    public static final int TYPING_STATE_TIMEOUT_MS = 150;

    // Scroll synchronization
    public static final int SCROLL_SYNC_DEBOUNCE_MS = 100;

    // Navigation highlighting reset delay
    public static final int NAVIGATION_RESET_DELAY_MS = 30;

    // UI Configuration
    public static final int DEFAULT_EDITOR_TAB_SIZE = 4;

    // Sliding window cache configuration
    public static final int SMALL_SLIDING_WINDOW = 3;   // Memory-focused: current + 2 adjacent

    // Default choice - can be made configurable via settings
    public static final int DEFAULT_SLIDING_WINDOW = SMALL_SLIDING_WINDOW;

    // Maximum cache size should match or exceed window size
    public static final int MAX_CACHED_DIFF_PANELS = Math.max(10, DEFAULT_SLIDING_WINDOW);

    // Memory management thresholds
    public static final int MEMORY_HIGH_THRESHOLD_PERCENT = 70; // Memory usage threshold for cleanup

    private PerformanceConstants() {} // Prevent instantiation
}
