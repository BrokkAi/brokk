package io.github.jbellis.brokk.difftool.performance;

/**
 * Constants for performance optimization configurations.
 * Centralizes all magic numbers and strings used in diff tool performance optimizations.
 */
public final class PerformanceConstants {

    // File size thresholds
    public static final long LARGE_FILE_THRESHOLD_BYTES = 10 * 1024 * 1024; // 10MB

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
    public static final int MAX_CACHED_DIFF_PANELS = 5;

    private PerformanceConstants() {} // Prevent instantiation
}