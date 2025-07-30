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

    // Scroll throttling mode configuration (mutable for developer UI)
    public static volatile boolean ENABLE_FRAME_BASED_THROTTLING = false; // Frame-based throttling
    public static volatile boolean ENABLE_ADAPTIVE_THROTTLING = true; // Adaptive mode (default)
    public static volatile int SCROLL_FRAME_RATE_MS = 16; // 60fps default (configurable via UI)

    // Frame rate presets for UI dropdown
    public static final int FRAME_60FPS = 16;  // Gaming-smooth (recommended)
    public static final int FRAME_30FPS = 33;  // Standard UI
    public static final int FRAME_20FPS = 50;  // Conservative (equivalent to debounce delay)

    // Adaptive throttling thresholds
    public static final int ADAPTIVE_MODE_LINE_THRESHOLD = 1000; // Switch to frame mode for files > 1000 lines
    public static final int ADAPTIVE_MODE_DELTA_THRESHOLD = 50;  // Switch to frame mode for > 50 deltas
    public static final long ADAPTIVE_MODE_PERFORMANCE_THRESHOLD_MS = 5; // Switch if mapping takes > 5ms
    public static final int ADAPTIVE_MODE_RAPID_SCROLL_THRESHOLD = 30; // Events per second to trigger frame mode

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

    /**
     * Validates scroll throttling configuration to ensure only one mode is active.
     * If multiple modes are enabled, prioritizes adaptive throttling, then frame-based.
     *
     * @return true if configuration was changed, false if already valid
     */
    public static boolean validateScrollThrottlingConfig() {
        int activeCount = 0;
        if (ENABLE_ADAPTIVE_THROTTLING) activeCount++;
        if (ENABLE_FRAME_BASED_THROTTLING) activeCount++;

        if (activeCount > 1) {
            // Auto-resolve: prioritize adaptive throttling
            if (ENABLE_ADAPTIVE_THROTTLING) {
                ENABLE_FRAME_BASED_THROTTLING = false;
            }
            return true; // Configuration was changed
        }

        return false; // Configuration was already valid
    }

    /**
     * Gets a human-readable description of the current scroll throttling mode.
     */
    public static String getCurrentScrollMode() {
        if (ENABLE_ADAPTIVE_THROTTLING) {
            return "Adaptive (Dynamic mode selection)";
        } else if (ENABLE_FRAME_BASED_THROTTLING) {
            return String.format("Frame-Based (%dms / %.1f FPS)",
                                SCROLL_FRAME_RATE_MS, 1000.0 / SCROLL_FRAME_RATE_MS);
        } else {
            return "Immediate (No throttling)";
        }
    }

    private PerformanceConstants() {} // Prevent instantiation
}
