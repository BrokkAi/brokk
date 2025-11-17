package ai.brokk.util;

import com.google.common.base.Splitter;
import java.awt.Frame;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import javax.swing.KeyStroke;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Global (project-agnostic) UI settings.
 *
 * <p>Stores: - mainWindow.bounds: x,y,width,height - split.horizontal: int (pixels) - split.leftVertical: int (pixels)
 * - split.rightVertical: int (pixels) - drawers.dependencies.open: boolean - drawers.dependencies.proportion: double
 * (0..1) - drawers.terminal.open: boolean - drawers.terminal.proportion: double (0..1)
 *
 * <p>Values are stored in a platform-appropriate app support folder: - Windows: %APPDATA%/Brokk/ui.properties
 * (fallback: ~/AppData/Roaming/Brokk) - macOS: ~/Library/Application Support/Brokk/ui.properties - Linux:
 * $XDG_CONFIG_HOME/Brokk/ui.properties (fallback: ~/.config/Brokk/ui.properties)
 */
public final class GlobalUiSettings {
    private static final Logger logger = LogManager.getLogger(GlobalUiSettings.class);

    private static final String KEY_MAIN_BOUNDS = "mainWindow.bounds";
    private static final String KEY_SPLIT_HORIZONTAL = "split.horizontal";
    private static final String KEY_SPLIT_LEFT_VERTICAL = "split.leftVertical";
    private static final String KEY_SPLIT_RIGHT_VERTICAL = "split.rightVertical";

    private static final String KEY_DEP_OPEN = "drawers.dependencies.open";
    private static final String KEY_DEP_PROP = "drawers.dependencies.proportion";
    private static final String KEY_TERM_OPEN = "drawers.terminal.open";
    private static final String KEY_TERM_PROP = "drawers.terminal.proportion";
    private static final String KEY_TERM_LASTTAB = "drawers.terminal.lastTab";
    private static final String KEY_PERSIST_PER_PROJECT_BOUNDS = "window.persistPerProjectBounds";
    private static final String KEY_DIFF_UNIFIED_VIEW = "diff.unifiedView";
    private static final String KEY_DIFF_SHOW_BLANK_LINES = "diff.showBlankLines";
    private static final String KEY_DIFF_SHOW_ALL_LINES = "diff.showAllLines";
    private static final String KEY_DIFF_SHOW_BLAME = "diff.showBlame";
    private static final String KEY_EDITOR_FONT_SIZE = "editor.fontSize";
    private static final String KEY_DIFF_FONT_SIZE = "diff.fontSize";
    private static final String KEY_PREVIEW_FONT_SIZE = "preview.fontSize";
    private static final String KEYBIND_PREFIX = "keybinding.";
    private static final String KEY_SHOW_COST_NOTIFICATIONS = "notifications.cost.enabled";
    private static final String KEY_SHOW_ERROR_NOTIFICATIONS = "notifications.error.enabled";
    private static final String KEY_SHOW_CONFIRM_NOTIFICATIONS = "notifications.confirm.enabled";
    private static final String KEY_SHOW_INFO_NOTIFICATIONS = "notifications.info.enabled";
    private static final String KEY_SHOW_FREE_INTERNAL_LLM_COST_NOTIFICATIONS =
            "notifications.cost.geminiFlashLite.enabled";
    private static final String KEY_UI_ADVANCED_MODE = "ui.advancedMode";
    private static final String KEY_UI_VERTICAL_ACTIVITY_LAYOUT = "ui.verticalActivityLayout";
    private static final String KEY_INSTRUCTIONS_TAB_INSERT_INDENTATION = "instructions.tab.insertIndentation";
    private static final String KEY_VERTICAL_LAYOUT_LEFT_SPLIT = "verticalLayout.leftSplit";
    private static final String KEY_VERTICAL_LAYOUT_HORIZONTAL_SPLIT = "verticalLayout.horizontalSplit";

    private static volatile @Nullable Properties cachedProps;

    private GlobalUiSettings() {}

    private static Path getUiPropertiesFile() {
        return BrokkConfigPaths.getGlobalConfigDir().resolve("ui.properties");
    }

    private static synchronized Properties loadProps() {
        if (cachedProps != null) {
            return cachedProps;
        }
        var props = new Properties();
        try {
            var configDir = BrokkConfigPaths.getGlobalConfigDir();
            Files.createDirectories(configDir);
            var file = getUiPropertiesFile();
            if (Files.exists(file)) {
                try (var reader = Files.newBufferedReader(file)) {
                    props.load(reader);
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to load global UI settings: {}", e.getMessage());
        }
        cachedProps = props;
        return cachedProps;
    }

    private static synchronized void saveProps(Properties props) {
        try {
            var configDir = BrokkConfigPaths.getGlobalConfigDir();
            Files.createDirectories(configDir);
            AtomicWrites.atomicSaveProperties(getUiPropertiesFile(), props, "Brokk global UI settings");
        } catch (IOException e) {
            logger.warn("Failed to save global UI settings: {}", e.getMessage());
        }
    }

    // --- Keybinding persistence ---
    public static KeyStroke getKeybinding(String id, KeyStroke fallback) {
        var props = loadProps();
        var raw = props.getProperty(KEYBIND_PREFIX + id);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            var parts = Splitter.on(',').splitToList(raw);
            if (parts.size() != 2) return fallback;
            int keyCode = Integer.parseInt(parts.get(0));
            int modifiers = Integer.parseInt(parts.get(1));
            var ks = KeyStroke.getKeyStroke(keyCode, modifiers);
            return ks != null ? ks : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    public static void saveKeybinding(String id, KeyStroke stroke) {
        var props = loadProps();
        int keyCode = stroke.getKeyCode();
        int modifiers = stroke.getModifiers();
        props.setProperty(KEYBIND_PREFIX + id, keyCode + "," + modifiers);
        saveProps(props);
    }

    // Main window bounds
    public static Rectangle getMainWindowBounds() {
        var props = loadProps();
        var raw = props.getProperty(KEY_MAIN_BOUNDS);
        if (raw == null || raw.isBlank()) {
            return new Rectangle(-1, -1, -1, -1);
        }
        try {
            var parts = Splitter.on(',').splitToList(raw);
            if (parts.size() != 4) return new Rectangle(-1, -1, -1, -1);
            int x = Integer.parseInt(parts.get(0).trim());
            int y = Integer.parseInt(parts.get(1).trim());
            int w = Integer.parseInt(parts.get(2).trim());
            int h = Integer.parseInt(parts.get(3).trim());
            return new Rectangle(x, y, w, h);
        } catch (Exception e) {
            logger.debug("Invalid main bounds value '{}'", raw);
            return new Rectangle(-1, -1, -1, -1);
        }
    }

    public static void saveMainWindowBounds(Frame frame) {
        var props = loadProps();
        String value = "%d,%d,%d,%d".formatted(frame.getX(), frame.getY(), frame.getWidth(), frame.getHeight());
        props.setProperty(KEY_MAIN_BOUNDS, value);
        saveProps(props);
    }

    // Split positions (pixels)
    public static int getHorizontalSplitPosition() {
        return getInt(KEY_SPLIT_HORIZONTAL);
    }

    public static void saveHorizontalSplitPosition(int px) {
        setInt(KEY_SPLIT_HORIZONTAL, px);
    }

    public static int getLeftVerticalSplitPosition() {
        return getInt(KEY_SPLIT_LEFT_VERTICAL);
    }

    public static void saveLeftVerticalSplitPosition(int px) {
        setInt(KEY_SPLIT_LEFT_VERTICAL, px);
    }

    public static int getRightVerticalSplitPosition() {
        return getInt(KEY_SPLIT_RIGHT_VERTICAL);
    }

    public static void saveRightVerticalSplitPosition(int px) {
        setInt(KEY_SPLIT_RIGHT_VERTICAL, px);
    }

    // Drawers: open flags
    public static boolean isDependenciesDrawerOpen() {
        return getBoolean(KEY_DEP_OPEN, false);
    }

    public static void saveDependenciesDrawerOpen(boolean open) {
        setBoolean(KEY_DEP_OPEN, open);
    }

    public static boolean isTerminalDrawerOpen() {
        return getBoolean(KEY_TERM_OPEN, false);
    }

    public static void saveTerminalDrawerOpen(boolean open) {
        setBoolean(KEY_TERM_OPEN, open);
    }

    // Drawers: proportions (0..1)
    public static double getDependenciesDrawerProportion() {
        return getDouble(KEY_DEP_PROP, -1.0);
    }

    public static void saveDependenciesDrawerProportion(double prop) {
        setDouble(KEY_DEP_PROP, clampProportion(prop));
    }

    public static double getTerminalDrawerProportion() {
        return getDouble(KEY_TERM_PROP, -1.0);
    }

    public static void saveTerminalDrawerProportion(double prop) {
        setDouble(KEY_TERM_PROP, clampProportion(prop));
    }

    // Drawer: last tab ("terminal" or "tasks")
    public static @Nullable String getTerminalDrawerLastTab() {
        var props = loadProps();
        var raw = props.getProperty(KEY_TERM_LASTTAB);
        if (raw == null || raw.isBlank()) return null;
        var norm = raw.trim().toLowerCase(Locale.ROOT);
        return ("terminal".equals(norm) || "tasks".equals(norm)) ? norm : null;
    }

    public static void saveTerminalDrawerLastTab(String tab) {
        var norm = tab.trim().toLowerCase(Locale.ROOT);
        if (!"terminal".equals(norm) && !"tasks".equals(norm)) {
            return;
        }
        var props = loadProps();
        props.setProperty(KEY_TERM_LASTTAB, norm);
        saveProps(props);
    }

    // Window bounds persistence preference (default: true = per-project)
    public static boolean isPersistPerProjectBounds() {
        return getBoolean(KEY_PERSIST_PER_PROJECT_BOUNDS, true);
    }

    public static void savePersistPerProjectBounds(boolean persist) {
        setBoolean(KEY_PERSIST_PER_PROJECT_BOUNDS, persist);
    }

    // Diff view preferences
    public static boolean isDiffUnifiedView() {
        return getBoolean(KEY_DIFF_UNIFIED_VIEW, false);
    }

    public static void saveDiffUnifiedView(boolean unified) {
        setBoolean(KEY_DIFF_UNIFIED_VIEW, unified);
    }

    public static boolean isDiffShowBlankLines() {
        return getBoolean(KEY_DIFF_SHOW_BLANK_LINES, false);
    }

    public static void saveDiffShowBlankLines(boolean show) {
        setBoolean(KEY_DIFF_SHOW_BLANK_LINES, show);
    }

    public static boolean isDiffShowAllLines() {
        return getBoolean(KEY_DIFF_SHOW_ALL_LINES, false);
    }

    public static void saveDiffShowAllLines(boolean show) {
        setBoolean(KEY_DIFF_SHOW_ALL_LINES, show);
    }

    public static boolean isDiffShowBlame() {
        return getBoolean(KEY_DIFF_SHOW_BLAME, false);
    }

    public static void saveDiffShowBlame(boolean show) {
        setBoolean(KEY_DIFF_SHOW_BLAME, show);
    }

    /**
     * Get unified editor font size for all editor components (diff panels, preview panels, etc.)
     * Migrates from old diff.fontSize or preview.fontSize settings if found.
     */
    public static float getEditorFontSize() {
        var props = loadProps();
        var raw = props.getProperty(KEY_EDITOR_FONT_SIZE);

        // If unified setting doesn't exist, migrate from old settings
        if (raw == null || raw.isBlank()) {
            // Try diff setting first (most commonly used)
            raw = props.getProperty(KEY_DIFF_FONT_SIZE);
            if (raw == null || raw.isBlank()) {
                // Try preview setting
                raw = props.getProperty(KEY_PREVIEW_FONT_SIZE);
            }

            // If we found an old setting, migrate it
            if (raw != null && !raw.isBlank()) {
                try {
                    float migrated = Float.parseFloat(raw.trim());
                    if (migrated > 0) {
                        saveEditorFontSize(migrated);
                        return migrated;
                    }
                } catch (Exception e) {
                    logger.debug("Failed to migrate old font size setting", e);
                }
            }
            return -1.0f;
        }

        try {
            return Float.parseFloat(raw.trim());
        } catch (Exception e) {
            return -1.0f;
        }
    }

    /**
     * Save unified editor font size for all editor components.
     */
    public static void saveEditorFontSize(float fontSize) {
        if (fontSize <= 0) return;
        var props = loadProps();
        props.setProperty(KEY_EDITOR_FONT_SIZE, Float.toString(fontSize));
        saveProps(props);
    }

    // Cost notifications preference (default: true)
    public static boolean isShowCostNotifications() {
        return getBoolean(KEY_SHOW_COST_NOTIFICATIONS, true);
    }

    public static void saveShowCostNotifications(boolean show) {
        setBoolean(KEY_SHOW_COST_NOTIFICATIONS, show);
    }

    public static boolean isShowErrorNotifications() {
        return getBoolean(KEY_SHOW_ERROR_NOTIFICATIONS, true);
    }

    public static void saveShowErrorNotifications(boolean show) {
        setBoolean(KEY_SHOW_ERROR_NOTIFICATIONS, show);
    }

    public static boolean isShowConfirmNotifications() {
        return getBoolean(KEY_SHOW_CONFIRM_NOTIFICATIONS, true);
    }

    public static void saveShowConfirmNotifications(boolean show) {
        setBoolean(KEY_SHOW_CONFIRM_NOTIFICATIONS, show);
    }

    public static boolean isShowInfoNotifications() {
        return getBoolean(KEY_SHOW_INFO_NOTIFICATIONS, true);
    }

    public static void saveShowInfoNotifications(boolean show) {
        setBoolean(KEY_SHOW_INFO_NOTIFICATIONS, show);
    }

    public static boolean isShowFreeInternalLLMCostNotifications() {
        return getBoolean(KEY_SHOW_FREE_INTERNAL_LLM_COST_NOTIFICATIONS, false);
    }

    public static void saveShowFreeInternalLLMCostNotifications(boolean show) {
        setBoolean(KEY_SHOW_FREE_INTERNAL_LLM_COST_NOTIFICATIONS, show);
    }

    // Advanced mode preference (default: false = easy mode)
    public static boolean isAdvancedMode() {
        return getBoolean(KEY_UI_ADVANCED_MODE, false);
    }

    public static void saveAdvancedMode(boolean advanced) {
        setBoolean(KEY_UI_ADVANCED_MODE, advanced);
    }

    public static boolean isVerticalActivityLayout() {
        return getBoolean(KEY_UI_VERTICAL_ACTIVITY_LAYOUT, false);
    }

    public static void saveVerticalActivityLayout(boolean enabled) {
        setBoolean(KEY_UI_VERTICAL_ACTIVITY_LAYOUT, enabled);
    }

    // Instructions panel preferences
    public static boolean isInstructionsTabInsertIndentation() {
        return getBoolean(KEY_INSTRUCTIONS_TAB_INSERT_INDENTATION, false);
    }

    public static void saveInstructionsTabInsertIndentation(boolean enabled) {
        setBoolean(KEY_INSTRUCTIONS_TAB_INSERT_INDENTATION, enabled);
    }

    // Vertical layout split positions (pixels)
    public static int getVerticalLayoutLeftSplitPosition() {
        return getInt(KEY_VERTICAL_LAYOUT_LEFT_SPLIT);
    }

    public static void saveVerticalLayoutLeftSplitPosition(int px) {
        setInt(KEY_VERTICAL_LAYOUT_LEFT_SPLIT, px);
    }

    public static int getVerticalLayoutHorizontalSplitPosition() {
        return getInt(KEY_VERTICAL_LAYOUT_HORIZONTAL_SPLIT);
    }

    public static void saveVerticalLayoutHorizontalSplitPosition(int px) {
        setInt(KEY_VERTICAL_LAYOUT_HORIZONTAL_SPLIT, px);
    }

    private static int getInt(String key) {
        var props = loadProps();
        try {
            var raw = props.getProperty(key);
            if (raw == null || raw.isBlank()) return -1;
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static void setInt(String key, int value) {
        if (value <= 0) return;
        var props = loadProps();
        props.setProperty(key, Integer.toString(value));
        saveProps(props);
    }

    private static boolean getBoolean(String key, boolean def) {
        var props = loadProps();
        var raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) return def;
        return Boolean.parseBoolean(raw.trim());
    }

    private static void setBoolean(String key, boolean value) {
        var props = loadProps();
        props.setProperty(key, Boolean.toString(value));
        saveProps(props);
    }

    private static double getDouble(String key, double def) {
        var props = loadProps();
        var raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) return def;
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static void setDouble(String key, double value) {
        var props = loadProps();
        props.setProperty(key, Double.toString(value));
        saveProps(props);
    }

    private static double clampProportion(double p) {
        if (Double.isNaN(p) || Double.isInfinite(p)) return -1.0;
        if (p <= 0.0 || p >= 1.0) return -1.0;
        // keep a safe margin to avoid degenerate layouts
        return Math.max(0.05, Math.min(0.95, p));
    }

    /**
     * Test hook: clear the cached properties so a new config dir (e.g., via brokk.ui.config.dir)
     * is picked up by the next loadProps() call.
     */
    public static synchronized void resetForTests() {
        cachedProps = null;
    }
}
