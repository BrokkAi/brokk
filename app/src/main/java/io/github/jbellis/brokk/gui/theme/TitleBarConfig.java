package io.github.jbellis.brokk.gui.theme;

import java.awt.Color;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable configuration for title bar styling and behavior.
 * This configuration is read from theme JSON files and provides
 * flexible customization of macOS title bar appearance.
 */
public record TitleBarConfig(
        boolean enabled,
        @Nullable Color backgroundColor,
        @Nullable Color foregroundColor,
        int topPadding,
        int leftPadding,
        int bottomPadding,
        int rightPadding) {
    /**
     * Creates a disabled title bar configuration.
     */
    public static TitleBarConfig disabled() {
        return new TitleBarConfig(false, null, null, 0, 0, 0, 0);
    }

    /**
     * Creates a title bar configuration with default values.
     */
    public static TitleBarConfig withDefaults() {
        return new TitleBarConfig(
                true, // enabled
                null, // backgroundColor (uses UIManager default)
                null, // foregroundColor (uses UIManager default)
                4, // topPadding
                80, // leftPadding (for window controls)
                4, // bottomPadding
                0 // rightPadding
                );
    }

    /**
     * Creates a title bar configuration with specific colors and default padding.
     */
    public static TitleBarConfig withColors(Color backgroundColor, Color foregroundColor) {
        return new TitleBarConfig(
                true, // enabled
                backgroundColor, // backgroundColor
                foregroundColor, // foregroundColor
                4, // topPadding
                80, // leftPadding (for window controls)
                4, // bottomPadding
                0 // rightPadding
                );
    }

    /**
     * Creates a title bar configuration with all custom values.
     */
    public static TitleBarConfig custom(
            boolean enabled,
            Color backgroundColor,
            Color foregroundColor,
            int topPadding,
            int leftPadding,
            int bottomPadding,
            int rightPadding) {
        return new TitleBarConfig(
                enabled, backgroundColor, foregroundColor, topPadding, leftPadding, bottomPadding, rightPadding);
    }
}
