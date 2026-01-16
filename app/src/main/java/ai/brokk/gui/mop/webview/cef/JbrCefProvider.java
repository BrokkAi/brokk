package ai.brokk.gui.mop.webview.cef;

import ai.brokk.project.MainProject;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.util.Environment;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.CefApp;
import org.cef.CefSettings;
import org.cef.handler.CefAppHandlerAdapter;
import org.jetbrains.annotations.Nullable;

/**
 * CEF provider for JBR (JetBrains Runtime) with bundled JCEF.
 *
 * <p>This provider is used in production jDeploy builds where:
 * <ul>
 *   <li>The JVM is JBR with JCEF variant</li>
 *   <li>JCEF frameworks are bundled in the app</li>
 *   <li>The {@code jdeploy.launcher.path} system property is set</li>
 * </ul>
 */
public class JbrCefProvider implements CefAppProvider {

    private static final Logger logger = LogManager.getLogger(JbrCefProvider.class);

    @Override
    public boolean isAvailable() {
        // Must be running in jDeploy
        String launcherPath = System.getProperty("jdeploy.launcher.path");
        if (launcherPath == null || launcherPath.isEmpty()) {
            logger.debug("jdeploy.launcher.path not set, JBR provider not available");
            return false;
        }

        // Must have JBR JCEF resources available (platform-specific check)
        if (!hasJcefResources()) {
            logger.debug("JBR JCEF resources not found, JBR provider not available");
            return false;
        }

        logger.info("JBR CEF provider is available");
        return true;
    }

    /**
     * Checks if JCEF resources are available for the current platform.
     */
    private static boolean hasJcefResources() {
        if (Environment.isMacOs()) {
            return findMacOsFrameworksDir() != null;
        } else if (Environment.isLinux()) {
            String javaHome = System.getProperty("java.home");
            if (javaHome == null) return false;
            return Files.exists(Paths.get(javaHome, "lib", "jcef_helper"));
        } else if (Environment.isWindows()) {
            String javaHome = System.getProperty("java.home");
            if (javaHome == null) return false;
            return Files.exists(Paths.get(javaHome, "bin", "jcef_helper.exe"));
        }
        return false;
    }

    @Override
    @SuppressWarnings("removal") // getInstance methods are deprecated but still functional
    public CefApp createCefApp(@Nullable CefAppHandlerAdapter stateHandler) {
        logger.info("JbrCefProvider.createCefApp() called");

        CefSettings settings = new CefSettings();
        settings.windowless_rendering_enabled = false;
        // Theme-aware background to reduce flash while loading
        String themeName = MainProject.getTheme();
        boolean isDark = !"BrokkLight".equals(themeName) && !"BrokkLightPlus".equals(themeName);
        Color bgColor = ThemeColors.getColor(isDark, ThemeColors.CHAT_BACKGROUND);
        settings.background_color =
                settings.new ColorType(0xFF, bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue());

        // Set a unique cache path to prevent conflicts
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            String appPathHash = getAppPathHash();
            Path cacheDir = Paths.get(userHome, ".brokk", "cef-cache", "jbr", appPathHash);
            settings.cache_path = cacheDir.toString();
            logger.info("Set CEF cache path to: {}", cacheDir);
        }

        // Configure resource paths for JBR's bundled Chromium
        configureResourcePaths(settings);

        if (stateHandler != null) {
            CefApp.addAppHandler(stateHandler);
        }

        logger.info("Calling CefApp.getInstance()");
        return CefApp.getInstance(settings);
    }

    /**
     * Generates a short hash from the jdeploy.launcher.path to create unique cache directories.
     */
    private String getAppPathHash() {
        String launcherPath = System.getProperty("jdeploy.launcher.path");
        if (launcherPath == null || launcherPath.isEmpty()) {
            return "default";
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(launcherPath.getBytes(StandardCharsets.UTF_8));
            String hashStr = IntStream.range(0, 8)
                    .mapToObj(i -> String.format("%02x", hash[i]))
                    .collect(Collectors.joining());
            logger.debug("Generated cache hash {} for launcher path: {}", hashStr, launcherPath);
            return hashStr;
        } catch (NoSuchAlgorithmException e) {
            logger.warn("Failed to generate hash for launcher path", e);
            return "default";
        }
    }

    /**
     * Finds the Frameworks directory for JBR JCEF on macOS using jdeploy.launcher.path.
     *
     * <p>Supports two formats for the launcher path:
     * <ul>
     *   <li>Binary inside MacOS: {@code /path/to/App.app/Contents/MacOS/AppName}</li>
     *   <li>App bundle directly: {@code /path/to/App.app}</li>
     * </ul>
     */
    private static Path findMacOsFrameworksDir() {
        String launcherPath = System.getProperty("jdeploy.launcher.path");
        if (launcherPath == null || launcherPath.isEmpty()) {
            logger.debug("jdeploy.launcher.path not set, cannot find app bundle Frameworks");
            return null;
        }

        Path launcher = Paths.get(launcherPath);

        // Case 1: Path is the .app bundle itself
        if (launcherPath.endsWith(".app")) {
            Path frameworks = launcher.resolve("Contents/Frameworks");
            if (hasChromiumFramework(frameworks)) {
                logger.info("Found app bundle Frameworks at {}", frameworks);
                return frameworks;
            }
        }

        // Case 2: Path is binary inside MacOS - walk up to find Contents/Frameworks
        Path current = launcher;
        for (int i = 0; i < 5; i++) {
            current = current.getParent();
            if (current == null) {
                break;
            }
            if ("Contents".equals(current.getFileName().toString())) {
                Path frameworks = current.resolve("Frameworks");
                if (hasChromiumFramework(frameworks)) {
                    logger.info("Found app bundle Frameworks at {}", frameworks);
                    return frameworks;
                }
            }
        }

        logger.debug("Chromium Embedded Framework.framework not found from launcher path: {}", launcherPath);
        return null;
    }

    private static boolean hasChromiumFramework(Path frameworksDir) {
        return Files.exists(frameworksDir.resolve("Chromium Embedded Framework.framework"));
    }

    /**
     * Configures resource and locale paths for JCEF.
     */
    private void configureResourcePaths(CefSettings settings) {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) {
            logger.warn("java.home is null, cannot configure resource paths");
            return;
        }

        Path javaHomePath = Paths.get(javaHome);

        if (Environment.isMacOs()) {
            configureMacOsPaths(settings);
        } else if (Environment.isLinux()) {
            configureLinuxPaths(settings, javaHomePath);
        } else if (Environment.isWindows()) {
            configureWindowsPaths(settings, javaHomePath);
        }
    }

    private void configureMacOsPaths(CefSettings settings) {
        Path frameworksDir = findMacOsFrameworksDir();
        if (frameworksDir == null) {
            logger.error("Could not find Frameworks directory!");
            return;
        }

        Path frameworkPath = frameworksDir.resolve("Chromium Embedded Framework.framework");
        Path frameworkResources = frameworkPath.resolve("Resources");

        if (Files.exists(frameworkResources)) {
            settings.resources_dir_path = frameworkResources.toString();
            settings.locales_dir_path = frameworkResources.toString();
            logger.info("Set resources_dir_path = {}", settings.resources_dir_path);

            Path helperExe = frameworksDir.resolve("jcef Helper.app/Contents/MacOS/jcef Helper");
            if (Files.exists(helperExe)) {
                settings.browser_subprocess_path = helperExe.toString();
                logger.info("Set browser_subprocess_path = {}", helperExe);
            }
        } else {
            logger.warn("Framework resources not found at {}", frameworkResources);
        }
    }

    private void configureLinuxPaths(CefSettings settings, Path javaHomePath) {
        Path libPath = javaHomePath.resolve("lib");
        if (Files.exists(libPath.resolve("icudtl.dat"))) {
            settings.resources_dir_path = libPath.toString();
            settings.locales_dir_path = libPath.resolve("locales").toString();
            logger.info("Using Linux resources from {}", libPath);

            Path helperExe = libPath.resolve("jcef_helper");
            if (Files.exists(helperExe)) {
                settings.browser_subprocess_path = helperExe.toString();
                logger.info("Set browser_subprocess_path = {}", helperExe);
            } else {
                logger.warn("jcef_helper not found at {}", helperExe);
            }
        }
    }

    private void configureWindowsPaths(CefSettings settings, Path javaHomePath) {
        Path binPath = javaHomePath.resolve("bin");
        if (Files.exists(binPath.resolve("icudtl.dat"))) {
            settings.resources_dir_path = binPath.toString();
            settings.locales_dir_path = binPath.resolve("locales").toString();
            logger.info("Using Windows resources from {}", binPath);

            Path helperExe = binPath.resolve("jcef_helper.exe");
            if (Files.exists(helperExe)) {
                settings.browser_subprocess_path = helperExe.toString();
                logger.info("Set browser_subprocess_path = {}", helperExe);
            } else {
                logger.warn("jcef_helper.exe not found at {}", helperExe);
            }
        }
    }
}
