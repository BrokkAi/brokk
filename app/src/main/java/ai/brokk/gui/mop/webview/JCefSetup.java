package ai.brokk.gui.mop.webview;

import ai.brokk.util.Environment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.CefApp;
import org.cef.CefSettings;
import org.cef.handler.CefAppHandlerAdapter;

/**
 * Setup helper for JBR's bundled JCEF (JetBrains Runtime with JCEF).
 * Unlike jcefmaven, JBR already includes JCEF - no download needed.
 */
public class JCefSetup {
    private static final Logger logger = LogManager.getLogger(JCefSetup.class);

    /**
     * Creates and returns a configured CefApp using JBR's bundled JCEF.
     * This should only be called once; subsequent calls should use the cached instance.
     *
     * @param stateHandler optional handler for CefApp state changes (can be null)
     * @return the initialized CefApp instance
     */
    @SuppressWarnings("removal") // getInstance methods are deprecated but still functional
    public static CefApp createCefApp(@org.jetbrains.annotations.Nullable CefAppHandlerAdapter stateHandler) {
        logger.info("JCefSetup.createCefApp() called");

        CefSettings settings = new CefSettings();
        settings.windowless_rendering_enabled = false;
        // Dark background to reduce flash while loading
        settings.background_color = settings.new ColorType(0xFF, 37, 37, 37);

        // Set a unique cache path to prevent conflicts with other CEF apps
        // This avoids "unintended process singleton behavior" warnings/crashes
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            // Use a hash of the app path to keep different installations separate
            String appPathHash = getAppPathHash();
            Path cacheDir = Paths.get(userHome, ".brokk", "cef-cache", appPathHash);
            settings.cache_path = cacheDir.toString();
            logger.info("Set CEF cache path to: {}", cacheDir);
        }

        // Build command-line arguments for CEF
        List<String> args = new ArrayList<>();

        // GPU should work now that frameworks are properly ad-hoc signed
        // No special flags needed for macOS

        // Configure resource paths for JBR's bundled Chromium
        configureResourcePaths(settings, args);

        if (stateHandler != null) {
            CefApp.addAppHandler(stateHandler);
        }

        // Use the overload that accepts command-line args
        String[] argsArray = args.toArray(new String[0]);
        if (argsArray.length > 0) {
            logger.info("Passing args to CEF: {}", String.join(" ", argsArray));
        }

        logger.info("Calling CefApp.getInstance() with {} args", argsArray.length);
        return CefApp.getInstance(argsArray, settings);
    }

    /**
     * Creates a CefApp with default settings and no state handler.
     *
     * @return the initialized CefApp instance
     */
    public static CefApp createCefApp() {
        return createCefApp(null);
    }

    /**
     * Generates a short hash from the jdeploy.app.path system property to create
     * unique cache directories for different app installations.
     *
     * @return a short hash string, or "default" if no app path is set
     */
    private static String getAppPathHash() {
        String appPath = System.getProperty("jdeploy.app.path");
        if (appPath == null || appPath.isEmpty()) {
            logger.debug("jdeploy.app.path not set, using default cache path");
            return "default";
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(appPath.getBytes(StandardCharsets.UTF_8));
            // Use first 8 bytes (16 hex chars) for a reasonably unique but short hash
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            String hashStr = sb.toString();
            logger.debug("Generated cache hash {} for app path: {}", hashStr, appPath);
            return hashStr;
        } catch (Exception e) {
            logger.warn("Failed to generate hash for app path, using default", e);
            return "default";
        }
    }

    /**
     * Finds the Frameworks directory, preferring app bundle's ad-hoc signed frameworks.
     */
    private static Path findFrameworksDir() {
        logger.info("findFrameworksDir() called");

        // Use app bundle's Frameworks (ad-hoc signed to match main app)
        Path appBundleFrameworks = findAppBundleFrameworks();
        if (appBundleFrameworks != null) {
            logger.info("Using app bundle Frameworks: {}", appBundleFrameworks);
            return appBundleFrameworks;
        }

        // Fall back to JBR's bundled Frameworks
        String javaHome = System.getProperty("java.home");
        logger.info("java.home = {}", javaHome);

        if (javaHome != null) {
            Path javaHomePath = Paths.get(javaHome);

            // Walk up from java.home looking for Contents/Frameworks
            Path current = javaHomePath;
            for (int i = 0; i < 6; i++) {
                Path contentsDir = current;
                logger.debug("Checking directory: {}", contentsDir);
                if (contentsDir.getFileName() != null && contentsDir.getFileName().toString().equals("Contents")) {
                    Path frameworks = contentsDir.resolve("Frameworks");
                    logger.info("Found Contents dir, checking for Frameworks at {}", frameworks);
                    if (Files.exists(frameworks.resolve("Chromium Embedded Framework.framework"))) {
                        logger.info("Found JBR Frameworks at {}", frameworks);
                        return frameworks;
                    }
                }
                current = current.getParent();
                if (current == null) break;
            }
        }

        logger.warn("No Frameworks directory found!");
        return null;
    }

    /**
     * Tries to find the macOS app bundle's Frameworks directory.
     * This looks for a .app bundle in common locations.
     */
    private static Path findAppBundleFrameworks() {
        // Check if we're running from a jDeploy app by looking at the launcher process
        // The app bundle path might be in an environment variable or system property

        // Try common app locations
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            // Check ~/Applications for the app
            Path userApps = Paths.get(userHome, "Applications");
            Path appFrameworks = findFrameworksInAppDir(userApps);
            if (appFrameworks != null) {
                return appFrameworks;
            }
        }

        // Check /Applications
        Path systemApps = Paths.get("/Applications");
        Path appFrameworks = findFrameworksInAppDir(systemApps);
        if (appFrameworks != null) {
            return appFrameworks;
        }

        return null;
    }

    /**
     * Searches for Brokk*.app in the given directory and returns its Frameworks path.
     */
    private static Path findFrameworksInAppDir(Path appsDir) {
        if (!Files.isDirectory(appsDir)) {
            return null;
        }

        try (var stream = Files.list(appsDir)) {
            var brokkApps = stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("Brokk") && name.endsWith(".app");
                    })
                    .toList();

            for (Path appPath : brokkApps) {
                Path frameworks = appPath.resolve("Contents/Frameworks");
                if (Files.exists(frameworks.resolve("Chromium Embedded Framework.framework"))) {
                    logger.info("Found app bundle Frameworks at {}", frameworks);
                    return frameworks;
                }
            }
        } catch (Exception e) {
            logger.debug("Error searching for app bundles in {}: {}", appsDir, e.getMessage());
        }

        return null;
    }

    /**
     * Configures the resource and locale paths for JCEF based on the JBR installation.
     */
    private static void configureResourcePaths(CefSettings settings, List<String> args) {
        logger.info("configureResourcePaths() called");

        String javaHome = System.getProperty("java.home");
        if (javaHome == null) {
            logger.warn("java.home is null, cannot configure resource paths");
            return;
        }

        Path javaHomePath = Paths.get(javaHome);

        if (Environment.isMacOs()) {
            logger.info("Configuring for macOS");
            Path frameworksDir = findFrameworksDir();
            if (frameworksDir == null) {
                logger.error("Could not find Frameworks directory!");
                return;
            }

            Path frameworkPath = frameworksDir.resolve("Chromium Embedded Framework.framework");
            Path frameworkResources = frameworkPath.resolve("Resources");

            if (Files.exists(frameworkResources)) {
                // Set CefSettings paths (required for CEF to find resources)
                settings.resources_dir_path = frameworkResources.toString();
                settings.locales_dir_path = frameworkResources.toString();
                logger.info("Set resources_dir_path = {}", settings.resources_dir_path);
                logger.info("Set locales_dir_path = {}", settings.locales_dir_path);

                // Set browser subprocess path
                Path helperAppDir = frameworksDir.resolve("jcef Helper.app");
                Path helperExe = helperAppDir.resolve("Contents/MacOS/jcef Helper");
                if (Files.exists(helperExe)) {
                    settings.browser_subprocess_path = helperExe.toString();
                    logger.info("Set browser_subprocess_path = {}", helperExe);
                }

                logger.info("Added framework command-line args");
            } else {
                logger.warn("Framework resources not found at {}", frameworkResources);
            }
        } else if (Environment.isLinux()) {
            // On Linux, resources are typically in lib/
            Path libPath = javaHomePath.resolve("lib");
            if (Files.exists(libPath.resolve("icudtl.dat"))) {
                settings.resources_dir_path = libPath.toString();
                settings.locales_dir_path = libPath.resolve("locales").toString();
                logger.info("Using Linux resources from {}", libPath);

                // Set browser subprocess path - critical to prevent infinite spawning
                Path helperExe = libPath.resolve("jcef_helper");
                if (Files.exists(helperExe)) {
                    settings.browser_subprocess_path = helperExe.toString();
                    logger.info("Set browser_subprocess_path = {}", helperExe);
                } else {
                    logger.warn("jcef_helper not found at {}", helperExe);
                }
            }
        } else if (Environment.isWindows()) {
            // On Windows, resources are typically in bin/
            Path binPath = javaHomePath.resolve("bin");
            if (Files.exists(binPath.resolve("icudtl.dat"))) {
                settings.resources_dir_path = binPath.toString();
                settings.locales_dir_path = binPath.resolve("locales").toString();
                logger.info("Using Windows resources from {}", binPath);

                // Set browser subprocess path - critical to prevent infinite spawning
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
}
