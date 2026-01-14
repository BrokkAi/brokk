package ai.brokk.gui.mop.webview;

import ai.brokk.util.Environment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
                /*
                  args.add(String.format("--framework-dir-path=%s/Chromium Embedded Framework.framework", getLibPath()));
                args.add(String.format("--main-bundle-path=%s/jcef Helper.app", getLibPath()));
                args.add(String.format("--browser-subprocess-path=%s/jcef Helper.app/Contents/MacOS/jcef Helper", getLibPath()));

                 */

                // Pass framework paths as command-line args (needed for proper CEF initialization)
                args.add("--framework-dir-path=" + frameworkPath);
                args.add("--main-bundle-path=" + helperAppDir);
                args.add("--browser-subprocess-path=" + helperExe);
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
            }
        } else if (Environment.isWindows()) {
            // On Windows, resources are typically in bin/
            Path binPath = javaHomePath.resolve("bin");
            if (Files.exists(binPath.resolve("icudtl.dat"))) {
                settings.resources_dir_path = binPath.toString();
                settings.locales_dir_path = binPath.resolve("locales").toString();
                logger.info("Using Windows resources from {}", binPath);
            }
        }
    }
}
