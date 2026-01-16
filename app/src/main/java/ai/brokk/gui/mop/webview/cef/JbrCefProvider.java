package ai.brokk.gui.mop.webview.cef;

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
import org.jetbrains.annotations.Nullable;

/**
 * CEF provider for JBR (JetBrains Runtime) with bundled JCEF.
 *
 * <p>This provider is used in production jDeploy builds where:
 * <ul>
 *   <li>The JVM is JBR with JCEF variant</li>
 *   <li>JCEF frameworks are bundled in the app</li>
 *   <li>The {@code jdeploy.app.path} system property is set</li>
 * </ul>
 */
public class JbrCefProvider implements CefAppProvider {

    private static final Logger logger = LogManager.getLogger(JbrCefProvider.class);

    @Override
    public boolean isAvailable() {
        // Must be running in jDeploy
        String appPath = System.getProperty("jdeploy.app.path");
        if (appPath == null || appPath.isEmpty()) {
            logger.debug("jdeploy.app.path not set, JBR provider not available");
            return false;
        }

        // Must have JBR frameworks available
        Path frameworksDir = findFrameworksDir();
        if (frameworksDir == null) {
            logger.debug("JBR frameworks not found, JBR provider not available");
            return false;
        }

        logger.info("JBR CEF provider is available");
        return true;
    }

    @Override
    @SuppressWarnings("removal") // getInstance methods are deprecated but still functional
    public CefApp createCefApp(@Nullable CefAppHandlerAdapter stateHandler) {
        logger.info("JbrCefProvider.createCefApp() called");

        CefSettings settings = new CefSettings();
        settings.windowless_rendering_enabled = false;
        // Dark background to reduce flash while loading
        settings.background_color = settings.new ColorType(0xFF, 37, 37, 37);

        // Set a unique cache path to prevent conflicts
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            String appPathHash = getAppPathHash();
            Path cacheDir = Paths.get(userHome, ".brokk", "cef-cache", "jbr", appPathHash);
            settings.cache_path = cacheDir.toString();
            logger.info("Set CEF cache path to: {}", cacheDir);
        }

        // Build command-line arguments for CEF
        List<String> args = new ArrayList<>();

        // Configure resource paths for JBR's bundled Chromium
        configureResourcePaths(settings, args);

        if (stateHandler != null) {
            CefApp.addAppHandler(stateHandler);
        }

        String[] argsArray = args.toArray(new String[0]);
        if (argsArray.length > 0) {
            logger.info("Passing args to CEF: {}", String.join(" ", argsArray));
        }

        logger.info("Calling CefApp.getInstance() with {} args", argsArray.length);
        return CefApp.getInstance(argsArray, settings);
    }

    /**
     * Generates a short hash from the jdeploy.app.path to create unique cache directories.
     */
    private String getAppPathHash() {
        String appPath = System.getProperty("jdeploy.app.path");
        if (appPath == null || appPath.isEmpty()) {
            return "default";
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(appPath.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            String hashStr = sb.toString();
            logger.debug("Generated cache hash {} for app path: {}", hashStr, appPath);
            return hashStr;
        } catch (Exception e) {
            logger.warn("Failed to generate hash for app path", e);
            return "default";
        }
    }

    /**
     * Finds the Frameworks directory for JBR JCEF.
     */
    private static Path findFrameworksDir() {
        // Try app bundle's Frameworks first (macOS)
        Path appBundleFrameworks = findAppBundleFrameworks();
        if (appBundleFrameworks != null) {
            logger.info("Using app bundle Frameworks: {}", appBundleFrameworks);
            return appBundleFrameworks;
        }

        // Fall back to JBR's bundled Frameworks
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) {
            return null;
        }

        Path javaHomePath = Paths.get(javaHome);

        // Walk up from java.home looking for Contents/Frameworks
        Path current = javaHomePath;
        for (int i = 0; i < 6; i++) {
            if (current.getFileName() != null && current.getFileName().toString().equals("Contents")) {
                Path frameworks = current.resolve("Frameworks");
                if (Files.exists(frameworks.resolve("Chromium Embedded Framework.framework"))) {
                    logger.info("Found JBR Frameworks at {}", frameworks);
                    return frameworks;
                }
            }
            current = current.getParent();
            if (current == null) break;
        }

        return null;
    }

    /**
     * Finds Frameworks directory in macOS app bundle.
     */
    private static Path findAppBundleFrameworks() {
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            Path userApps = Paths.get(userHome, "Applications");
            Path appFrameworks = findFrameworksInAppDir(userApps);
            if (appFrameworks != null) {
                return appFrameworks;
            }
        }

        Path systemApps = Paths.get("/Applications");
        return findFrameworksInAppDir(systemApps);
    }

    /**
     * Searches for Brokk*.app in the given directory.
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
     * Configures resource and locale paths for JCEF.
     */
    private void configureResourcePaths(CefSettings settings, List<String> args) {
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
        Path frameworksDir = findFrameworksDir();
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
