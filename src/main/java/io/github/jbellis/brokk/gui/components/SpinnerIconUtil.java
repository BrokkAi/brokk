package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Utility class for managing and providing animated spinner icons.
 * Spinners are cached for performance and are theme-aware (dark/light).
 */
public final class SpinnerIconUtil {
    private static final Logger logger = LogManager.getLogger(SpinnerIconUtil.class);
    private static @Nullable Icon darkLarge;
    private static @Nullable Icon darkSmall;
    private static @Nullable Icon lightLarge;
    private static @Nullable Icon lightSmall;

    /** Private constructor to prevent instantiation of this utility class. */
    private SpinnerIconUtil() {}

    /**
     * Retrieves a spinner icon appropriate for the current theme and specified size.
     * Icons are cached. If a GIF animation is loaded, it will be restarted each time
     * it's retrieved from the cache after initial loading.
     *
     * @param chrome The application's Chrome instance, used to determine the current theme.
     * @param small  If true, a smaller version of the spinner is returned; otherwise, a larger version.
     * @return An {@link Icon} representing the spinner, or {@code null} if the icon resource cannot be found.
     *         This method must be called on the Event Dispatch Thread (EDT).
     */
    public static @Nullable Icon getSpinner(Chrome chrome, boolean small) {
        assert SwingUtilities.isEventDispatchThread() : "SpinnerIconUtil.getSpinner must be called on the EDT";
        GuiTheme theme = chrome.getTheme(); // may be null during early startup
        boolean isDark = theme.isDarkTheme();
        
        @Nullable Icon cached;
        if (isDark) {
            cached = small ? darkSmall : darkLarge;
        } else {
            cached = small ? lightSmall : lightLarge;
        }

        if (cached == null) {
            String suffix = small ? "_sm" : "";
            String path = "/icons/" + (isDark ? "spinner_dark" + suffix + ".gif" : "spinner_white" + suffix + ".gif");
            var url = SpinnerIconUtil.class.getResource(path);
            if (url == null) {
                logger.warn("Spinner icon resource not found: {}", path);
                return null;
            }
            ImageIcon original = new ImageIcon(url);
            // Re-wrapping the image in a new ImageIcon is a common way to restart GIF animations.
            cached = new ImageIcon(original.getImage());
            if (isDark) {
                if (small) {
                    darkSmall = cached;
                } else {
                    darkLarge = cached;
                }
            } else {
                if (small) {
                    lightSmall = cached;
                } else {
                    lightLarge = cached;
                }
            }
        }
        return cached;
    }
}
