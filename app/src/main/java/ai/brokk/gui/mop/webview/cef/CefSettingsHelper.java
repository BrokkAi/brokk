package ai.brokk.gui.mop.webview.cef;

import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.project.MainProject;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.CefSettings;
import org.jetbrains.annotations.Nullable;

/**
 * Common configuration utilities for CEF settings.
 *
 * <p>This class provides shared configuration logic used by all {@link CefAppProvider}
 * implementations, ensuring consistent behavior across different CEF backends.
 */
public final class CefSettingsHelper {

    private static final Logger logger = LogManager.getLogger(CefSettingsHelper.class);

    private CefSettingsHelper() {
        // Utility class
    }

    /**
     * Configures common CEF settings shared by all providers.
     *
     * <p>This includes:
     * <ul>
     *   <li>Disabling windowless rendering (we use windowed mode)</li>
     *   <li>Setting theme-aware background color to reduce flash while loading</li>
     *   <li>Redirecting CEF logs to the Brokk debug log file</li>
     * </ul>
     *
     * @param settings the CefSettings to configure
     */
    public static void configureCommonSettings(CefSettings settings) {
        settings.windowless_rendering_enabled = false;
        configureThemeBackground(settings);
        configureLogging(settings);
    }

    /**
     * Configures CEF logging to redirect to Brokk's debug log file.
     *
     * @param settings the CefSettings to configure
     */
    public static void configureLogging(CefSettings settings) {
        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            logger.warn("user.home not set, cannot configure CEF log path");
            return;
        }

        Path logFile = Paths.get(userHome, ".brokk", "cef.log");
        settings.log_file = logFile.toString();
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
        logger.debug("Set CEF log file to: {}", logFile);
    }

    /**
     * Sets the background color based on the current theme.
     *
     * <p>Uses {@link ThemeColors#CHAT_BACKGROUND} to match the chat panel background,
     * reducing visual flash when the browser first loads.
     *
     * @param settings the CefSettings to configure
     */
    public static void configureThemeBackground(CefSettings settings) {
        String themeName = MainProject.getTheme();
        boolean isDark = !GuiTheme.isLightThemeName(themeName);
        Color bgColor = ThemeColors.getColor(isDark, ThemeColors.CHAT_BACKGROUND);
        settings.background_color =
                settings.new ColorType(0xFF, bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue());
        logger.debug("Set CEF background color to {} (theme: {}, dark: {})", bgColor, themeName, isDark);
    }

    /**
     * Returns a CEF command-line argument for HiDPI scaling if needed.
     *
     * <p>Detects the display scale factor from AWT and returns the
     * {@code --force-device-scale-factor} argument for 4K and other high-DPI displays.
     *
     * @return the scale factor arg (e.g., "--force-device-scale-factor=2.0"), or null if not needed
     */
    @Nullable
    public static String getHiDpiArg() {
        try {
            var ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            var gd = ge.getDefaultScreenDevice();
            var gc = gd.getDefaultConfiguration();
            var transform = gc.getDefaultTransform();
            double scaleX = transform.getScaleX();

            if (scaleX > 1.0) {
                String arg = "--force-device-scale-factor=" + scaleX;
                logger.info("Detected HiDPI display, using {}", arg);
                return arg;
            }
        } catch (Exception e) {
            logger.debug("Could not detect display scale factor: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Configures the CEF cache path.
     *
     * @param settings the CefSettings to configure
     * @param providerSubdir subdirectory name for this provider (e.g., "jbr", "maven")
     * @param extraSubdir optional additional subdirectory (e.g., app path hash), may be null
     */
    public static void configureCachePath(CefSettings settings, String providerSubdir, @Nullable String extraSubdir) {
        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            logger.warn("user.home not set, cannot configure CEF cache path");
            return;
        }

        Path cacheDir;
        if (extraSubdir != null && !extraSubdir.isEmpty()) {
            cacheDir = Paths.get(userHome, ".brokk", "cef-cache", providerSubdir, extraSubdir);
        } else {
            cacheDir = Paths.get(userHome, ".brokk", "cef-cache", providerSubdir);
        }
        settings.cache_path = cacheDir.toString();
        logger.info("Set CEF cache path to: {}", cacheDir);
    }
}
