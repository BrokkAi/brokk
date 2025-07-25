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
    public static final long MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024; // 2MB - absolute maximum to prevent OOM

    // Timer delays (milliseconds)
    public static final int DEFAULT_UPDATE_TIMER_DELAY_MS = 400;
    public static final int DEFAULT_REDISPLAY_TIMER_DELAY_MS = 200;
    public static final int LARGE_FILE_UPDATE_TIMER_DELAY_MS = 800;
    public static final int LARGE_FILE_REDISPLAY_TIMER_DELAY_MS = 400;

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
    public static final int MEDIUM_SLIDING_WINDOW = 5;  // Balanced: current + 4 adjacent
    public static final int LARGE_SLIDING_WINDOW = 7;   // Performance-focused: current + 6 adjacent

    // Default choice - can be made configurable via settings
    public static final int DEFAULT_SLIDING_WINDOW = SMALL_SLIDING_WINDOW;

    // Maximum cache size should match or exceed window size
    public static final int MAX_CACHED_DIFF_PANELS = Math.max(10, DEFAULT_SLIDING_WINDOW);

    // Background preloading
    public static final int PRELOAD_DELAY_MS = 500; // Delay before preloading adjacent files

    // Memory management thresholds
    public static final int MEMORY_HIGH_THRESHOLD_PERCENT = 70; // Memory usage threshold for cleanup
    public static final int MEMORY_CRITICAL_THRESHOLD_PERCENT = 85; // Critical memory usage threshold

    private PerformanceConstants() {} // Prevent instantiation
}