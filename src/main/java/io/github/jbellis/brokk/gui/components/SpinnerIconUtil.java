package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;

public final class SpinnerIconUtil {
    private static final Logger logger = LogManager.getLogger(SpinnerIconUtil.class);
    private static Icon dark;
    private static Icon light;

    private SpinnerIconUtil() {}

    public static Icon getSpinner(Chrome chrome) {
        assert SwingUtilities.isEventDispatchThread() : "SpinnerIconUtil.getSpinner must be called on the EDT";
        GuiTheme theme = chrome.getTheme();       // may be null during early startup
        boolean isDark = theme != null && theme.isDarkTheme();
        Icon cached = isDark ? dark : light;
        if (cached == null) {
            String path = "/icons/" + (isDark ? "spinner_dark.gif" : "spinner_white.gif");
            var url = SpinnerIconUtil.class.getResource(path);
            if (url == null) {
                logger.warn("Spinner icon resource not found: {}", path);
                return null;
            }
            ImageIcon original = new ImageIcon(url);
            cached = new ImageIcon(original.getImage()); // restart animation
            if (isDark) {
                dark = cached;
            } else {
                light = cached;
            }
        }
        return cached;
    }
}
