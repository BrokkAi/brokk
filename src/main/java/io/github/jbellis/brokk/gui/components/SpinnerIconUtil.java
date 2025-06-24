package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.net.URL;

/**
 * Utility class for loading and caching spinner icons.
 * Ensures that spinner animations are reset when retrieved from the cache.
 */
public final class SpinnerIconUtil {
    private static final Logger logger = LogManager.getLogger(SpinnerIconUtil.class);
    private static @Nullable Icon darkLargeSpinner;
    private static @Nullable Icon darkSmallSpinner;
    private static @Nullable Icon lightLargeSpinner;
    private static @Nullable Icon lightSmallSpinner;

    private SpinnerIconUtil() {
        // Private constructor to prevent instantiation
    }

    /**
     * Retrieves a spinner icon appropriate for the current theme and specified size.
     * Icons are cached for performance. The animation of the GIF is reset each time
     * a new ImageIcon is created from the cached image data.
     * This method must be called on the Event Dispatch Thread.
     *
     * @param chrome The application's Chrome instance, used to determine the current theme.
     * @param small  If true, a small version of the spinner is returned; otherwise, a large version.
     * @return The requested {@link Icon}, or {@code null} if the icon resource cannot be found.
     */
    public static @Nullable Icon getSpinner(Chrome chrome, boolean small) {
        assert SwingUtilities.isEventDispatchThread() : "SpinnerIconUtil.getSpinner must be called on the EDT";

        GuiTheme theme = chrome.getTheme(); // May be null during early startup
        boolean isDarkTheme = theme != null && theme.isDarkTheme();

        // Determine which cached icon to use or update
        if (isDarkTheme) {
            if (small) {
                if (darkSmallSpinner == null) {
                    darkSmallSpinner = loadSpinnerIcon(true, true);
                }
                return darkSmallSpinner;
            } else {
                if (darkLargeSpinner == null) {
                    darkLargeSpinner = loadSpinnerIcon(true, false);
                }
                return darkLargeSpinner;
            }
        } else {
            if (small) {
                if (lightSmallSpinner == null) {
                    lightSmallSpinner = loadSpinnerIcon(false, true);
                }
                return lightSmallSpinner;
            } else {
                if (lightLargeSpinner == null) {
                    lightLargeSpinner = loadSpinnerIcon(false, false);
                }
                return lightLargeSpinner;
            }
        }
    }

    /**
     * Loads a spinner icon from the classpath.
     *
     * @param isDarkTheme True if the dark theme is active, false for light theme.
     * @param isSmall     True for the small spinner, false for the large one.
     * @return The loaded {@link ImageIcon}, or {@code null} if the resource is not found.
     */
    private static @Nullable ImageIcon loadSpinnerIcon(boolean isDarkTheme, boolean isSmall) {
        String sizeSuffix = isSmall ? "_sm" : "";
        String themePrefix = isDarkTheme ? "spinner_dark" : "spinner_white";
        String path = String.format("/icons/%s%s.gif", themePrefix, sizeSuffix);

        URL resourceUrl = SpinnerIconUtil.class.getResource(path);
        if (resourceUrl == null) {
            logger.warn("Spinner icon resource not found: {}", path);
            return null;
        }

        ImageIcon originalIcon = new ImageIcon(resourceUrl);
        // Create a new ImageIcon from the original's image to reset GIF animation
        return new ImageIcon(originalIcon.getImage());
    }
}
