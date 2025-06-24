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
    public static final int SCROLL_SYNC_DEBOUNCE_MS = 50;
    
    // Navigation highlighting reset delay
    public static final int NAVIGATION_RESET_DELAY_MS = 30;
    
    // UI Configuration
    public static final int DEFAULT_EDITOR_TAB_SIZE = 4;
    public static final int MAX_CACHED_DIFF_PANELS = 5;
    
    // UI Spacing (pixels)
    public static final int TOOLBAR_SMALL_SPACING_PX = 10;
    public static final int TOOLBAR_MEDIUM_SPACING_PX = 15;
    public static final int TOOLBAR_LARGE_SPACING_PX = 20;
    
    // Test constants
    public static final class TestConstants {
        // Performance thresholds for testing
        public static final long FAST_OPERATION_THRESHOLD_MS = 10;
        public static final long LARGE_DIFF_OPERATION_TIMEOUT_MS = 1000;
        public static final long VIEWPORT_FILTER_OPERATION_THRESHOLD_MS = 10;
        
        // Test file sizes
        public static final long TEST_SMALL_FILE_SIZE_BYTES = 1024 * 1024; // 1MB
        public static final long TEST_NORMAL_FILE_SIZE_BYTES = 5 * 1024 * 1024; // 5MB
        public static final long TEST_LARGE_FILE_SIZE_BYTES = 15 * 1024 * 1024; // 15MB
        public static final long TEST_VERY_LARGE_FILE_SIZE_BYTES = 50 * 1024 * 1024; // 50MB
        public static final long TEST_HUGE_FILE_SIZE_BYTES = 100 * 1024 * 1024; // 100MB
        
        // Test data sizes
        public static final int TEST_LARGE_DELTA_COUNT = 1000;
        public static final int TEST_DELTA_LINE_SPACING = 10;
        public static final int TEST_DELTA_SIZE = 5;
        public static final int TEST_MAX_VISIBLE_DELTAS = 20;
        public static final int TEST_LARGE_FILE_LINE_COUNT = 10000;
        public static final int TEST_LINE_MODIFICATION_INTERVAL = 100;
        public static final int TEST_EXPECTED_CHANGES_COUNT = 100;
        
        // Test viewport ranges
        public static final int TEST_VIEWPORT_START_LINE = 500;
        public static final int TEST_VIEWPORT_END_LINE = 600;
        
        // Cache testing
        public static final long TEST_CACHE_VALIDITY_MS = 100;
        public static final long TEST_SIMULATED_CALCULATION_TIME_MS = 50;
        public static final int TEST_EXPECTED_CACHE_SPEED_FACTOR = 5;
        
        // Optimization strategies
        public static final String OPTIMIZATION_STRATEGY_MINIMAL = "MINIMAL";
        public static final String OPTIMIZATION_STRATEGY_MODERATE = "MODERATE";
        
        private TestConstants() {} // Prevent instantiation
    }
    
    private PerformanceConstants() {} // Prevent instantiation
}