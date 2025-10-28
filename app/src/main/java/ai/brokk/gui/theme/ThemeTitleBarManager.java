package ai.brokk.gui.theme;

import ai.brokk.MainProject;
import com.formdev.flatlaf.util.SystemInfo;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Unified title bar manager that reads configuration from theme files.
 * Provides theme-driven title bar styling for macOS full window content.
 */
public class ThemeTitleBarManager {
    private static final Logger logger = LogManager.getLogger(ThemeTitleBarManager.class);
    private static final Set<JFrame> managedFrames = ConcurrentHashMap.newKeySet();

    /**
     * Applies title bar styling to a frame based on current theme configuration.
     */
    public static void applyTitleBar(JFrame frame, String title) {
        if (!SystemInfo.isMacOS || !SystemInfo.isMacFullWindowContentSupported) {
            return;
        }

        var config = loadTitleBarConfig();
        var titleBar = createTitleBar(title, config, frame);
        frame.add(titleBar, BorderLayout.NORTH);

        // Store components for later updates
        frame.getRootPane().putClientProperty("brokk.titleBar", titleBar);
        frame.getRootPane().putClientProperty("brokk.titleLabel", ((JPanel) titleBar).getComponent(0));
        frame.getRootPane().putClientProperty("brokk.titleBarConfig", config);

        managedFrames.add(frame);

        // Revalidate layout after dynamically adding title bar
        frame.revalidate();
        frame.repaint();
    }

    /**
     * Updates title bar styling for all managed frames when theme changes.
     */
    public static void updateAllTitleBars() {
        var config = loadTitleBarConfig();

        SwingUtilities.invokeLater(() -> {
            for (JFrame frame : managedFrames) {
                updateTitleBarStyling(frame, config);
            }
        });
    }

    /**
     * Updates title bar styling for a specific frame.
     */
    public static void updateTitleBarStyling(JFrame frame) {
        var config = loadTitleBarConfig();
        updateTitleBarStyling(frame, config);
    }

    /**
     * Removes a frame from management when it's disposed.
     */
    public static void removeFrame(JFrame frame) {
        managedFrames.remove(frame);
    }

    /**
     * Loads title bar configuration from current theme.
     */
    private static TitleBarConfig loadTitleBarConfig() {
        try {
            // Check if title bar is enabled
            boolean enabled = getThemeBoolean("Brokk.titleBar.enabled", true);
            if (!enabled) {
                return TitleBarConfig.disabled();
            }

            // Load colors
            String bgColorString = getThemeString("Brokk.titleBar.backgroundColor", "");
            Color backgroundColor = parseColor(bgColorString);
            String fgColorString = getThemeString("Brokk.titleBar.foregroundColor", "");
            Color foregroundColor = parseColor(fgColorString);

            // Load padding values
            int topPadding = getThemeInt("Brokk.titleBar.topPadding", 4);
            int leftPadding = getThemeInt("Brokk.titleBar.leftPadding", 80);
            int bottomPadding = getThemeInt("Brokk.titleBar.bottomPadding", 4);
            int rightPadding = getThemeInt("Brokk.titleBar.rightPadding", 0);

            return new TitleBarConfig(
                    enabled, backgroundColor, foregroundColor, topPadding, leftPadding, bottomPadding, rightPadding);
        } catch (Exception e) {
            logger.debug("Failed to load title bar configuration: {}", e.getMessage());
        }

        // Fallback: check if it's high contrast theme for backward compatibility
        if (GuiTheme.THEME_HIGH_CONTRAST.equalsIgnoreCase(MainProject.getTheme())) {
            return TitleBarConfig.withColors(Color.BLACK, Color.WHITE);
        }

        return TitleBarConfig.withDefaults();
    }

    /**
     * Creates a title bar component with the given configuration.
     */
    private static JPanel createTitleBar(String title, TitleBarConfig config, JFrame frame) {
        var titleBar = new JPanel(new BorderLayout());

        if (config.enabled()) {
            // Custom themed title bar
            // Apply background color
            if (config.backgroundColor() != null) {
                titleBar.setBackground(config.backgroundColor());
            } else {
                titleBar.setBackground(UIManager.getColor("Panel.background"));
            }

            // Apply padding
            titleBar.setBorder(BorderFactory.createEmptyBorder(
                    config.topPadding(), config.leftPadding(),
                    config.bottomPadding(), config.rightPadding()));

            // Create title label
            var label = new JLabel(title, SwingConstants.CENTER);
            if (config.foregroundColor() != null) {
                label.setForeground(config.foregroundColor());
            } else {
                label.setForeground(UIManager.getColor("Label.foreground"));
            }

            titleBar.add(label, BorderLayout.CENTER);
        } else {
            // Default macOS title bar styling (when disabled)
            titleBar.setBorder(BorderFactory.createEmptyBorder(4, 80, 4, 0)); // Padding for window controls
            var label = new JLabel(title, SwingConstants.CENTER);
            titleBar.add(label, BorderLayout.CENTER);
        }

        // Add double-click to maximize/minimize
        titleBar.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    if ((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) {
                        frame.setExtendedState(JFrame.NORMAL);
                    } else {
                        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                    }
                }
            }
        });

        return titleBar;
    }

    /**
     * Updates styling for an existing title bar.
     */
    private static void updateTitleBarStyling(JFrame frame, TitleBarConfig config) {
        var rootPane = frame.getRootPane();
        var titleBar = (JPanel) rootPane.getClientProperty("brokk.titleBar");
        var titleLabel = (JLabel) rootPane.getClientProperty("brokk.titleLabel");

        if (titleBar != null && titleLabel != null) {
            if (config.enabled()) {
                // Custom themed title bar
                // Update background color
                if (config.backgroundColor() != null) {
                    titleBar.setBackground(config.backgroundColor());
                } else {
                    titleBar.setBackground(UIManager.getColor("Panel.background"));
                }

                // Update foreground color
                if (config.foregroundColor() != null) {
                    titleLabel.setForeground(config.foregroundColor());
                } else {
                    titleLabel.setForeground(UIManager.getColor("Label.foreground"));
                }

                // Update padding
                titleBar.setBorder(BorderFactory.createEmptyBorder(
                        config.topPadding(), config.leftPadding(),
                        config.bottomPadding(), config.rightPadding()));

                // Ensure title bar is visible
                titleBar.setVisible(true);
            } else {
                // Default macOS title bar styling (when disabled)
                titleBar.setBackground(UIManager.getColor("Panel.background"));
                titleLabel.setForeground(UIManager.getColor("Label.foreground"));
                titleBar.setBorder(BorderFactory.createEmptyBorder(4, 80, 4, 0)); // Padding for window controls
                titleBar.setVisible(true);
            }

            // Repaint to apply changes
            titleBar.repaint();
            titleLabel.repaint();
        }
    }

    /**
     * Parses a color string in hex format.
     */
    private static @Nullable Color parseColor(@Nullable String colorString) {
        if (colorString == null || colorString.trim().isEmpty()) {
            return null;
        }

        try {
            if (colorString.startsWith("#")) {
                return Color.decode(colorString);
            }
        } catch (NumberFormatException e) {
            // Invalid color format, return null
        }

        return null;
    }

    /**
     * Get a boolean property from the current theme.
     */
    private static boolean getThemeBoolean(String key, boolean defaultValue) {
        try {
            Object value = UIManager.get(key);
            if (value instanceof Boolean b) {
                return b;
            }
            if (value instanceof String s) {
                return Boolean.parseBoolean(s);
            }
            return defaultValue;
        } catch (Exception e) {
            logger.debug("Failed to get theme boolean '{}': {}", key, e.getMessage());
            return defaultValue;
        }
    }

    /**
     * Get a string property from the current theme.
     */
    private static @Nullable String getThemeString(String key, @Nullable String defaultValue) {
        try {
            Object value = UIManager.get(key);
            if (value != null) {
                return value.toString();
            }
            return defaultValue;
        } catch (Exception e) {
            logger.debug("Failed to get theme string '{}': {}", key, e.getMessage());
            return defaultValue;
        }
    }

    /**
     * Get an integer property from the current theme.
     */
    private static int getThemeInt(String key, int defaultValue) {
        try {
            Object value = UIManager.get(key);
            if (value instanceof Number n) {
                return n.intValue();
            }
            if (value instanceof String s) {
                return Integer.parseInt(s);
            }
            return defaultValue;
        } catch (Exception e) {
            logger.debug("Failed to get theme integer '{}': {}", key, e.getMessage());
            return defaultValue;
        }
    }
}
