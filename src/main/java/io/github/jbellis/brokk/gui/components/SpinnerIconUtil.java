package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public final class SpinnerIconUtil {
    private static final Logger logger = LogManager.getLogger(SpinnerIconUtil.class);
    private static @Nullable Icon darkLarge;
    private static @Nullable Icon darkSmall;
    private static @Nullable Icon lightLarge;
    private static @Nullable Icon lightSmall;

    private SpinnerIconUtil() {}

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
            cached = new ImageIcon(original.getImage()); // restart animation
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
