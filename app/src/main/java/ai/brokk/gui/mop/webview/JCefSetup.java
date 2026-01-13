package ai.brokk.gui.mop.webview;

import ai.brokk.util.Environment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.cef.CefApp;
import org.cef.CefSettings;
import org.cef.handler.CefAppHandlerAdapter;

/**
 * Setup helper for JBR's bundled JCEF (JetBrains Runtime with JCEF).
 * Unlike jcefmaven, JBR already includes JCEF - no download needed.
 */
public class JCefSetup {

    /**
     * Creates and returns a configured CefApp using JBR's bundled JCEF.
     * This should only be called once; subsequent calls should use the cached instance.
     *
     * @param stateHandler optional handler for CefApp state changes (can be null)
     * @return the initialized CefApp instance
     */
    @SuppressWarnings("removal") // getInstance methods are deprecated but still functional
    public static CefApp createCefApp(@org.jetbrains.annotations.Nullable CefAppHandlerAdapter stateHandler) {
        CefSettings settings = new CefSettings();
        settings.windowless_rendering_enabled = false;
        // Dark background to reduce flash while loading
        settings.background_color = settings.new ColorType(0xFF, 37, 37, 37);

        // Build command-line arguments for CEF
        List<String> args = new ArrayList<>();

        // Configure resource paths for JBR's bundled Chromium
        configureResourcePaths(settings, args);

        if (stateHandler != null) {
            CefApp.addAppHandler(stateHandler);
        }

        // Use the overload that accepts command-line args
        String[] argsArray = args.toArray(new String[0]);
        if (argsArray.length > 0) {
            System.out.println("*** JCEF: Passing args to CEF: " + String.join(" ", argsArray) + " ***");
        }

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
     * Configures the resource and locale paths for JCEF based on the JBR installation.
     */
    private static void configureResourcePaths(CefSettings settings, List<String> args) {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) {
            return;
        }

        Path javaHomePath = Paths.get(javaHome);

        if (Environment.isMacOs()) {
            // On macOS, JBR structure is:
            // jbrsdk_jcef-X.X.X/Contents/Home (java.home)
            // jbrsdk_jcef-X.X.X/Contents/Frameworks/Chromium Embedded Framework.framework
            Path frameworkPath = javaHomePath
                    .resolve("../Frameworks/Chromium Embedded Framework.framework")
                    .normalize();
            Path frameworkResources = frameworkPath.resolve("Resources");

            if (Files.exists(frameworkResources)) {
                settings.resources_dir_path = frameworkResources.toString();
                settings.locales_dir_path = frameworkResources.resolve("locales").toString();

                // Also set browser subprocess path for the helper apps
                Path helperApp = javaHomePath
                        .resolve("../Frameworks/jcef Helper.app/Contents/MacOS/jcef Helper")
                        .normalize();
                if (Files.exists(helperApp)) {
                    settings.browser_subprocess_path = helperApp.toString();
                    System.out.println("*** JCEF: Using helper at " + helperApp + " ***");
                }

                // Pass framework path as command-line arg for CEF's native code
                args.add("--framework-dir-path=" + frameworkPath);
                args.add("--main-bundle-path=" + javaHomePath.resolve("..").normalize());
                args.add("--resources-dir-path=" + frameworkResources);

                System.out.println("*** JCEF: Using resources from " + frameworkResources + " ***");
            }
        } else if (Environment.isLinux()) {
            // On Linux, resources are typically in lib/
            Path libPath = javaHomePath.resolve("lib");
            if (Files.exists(libPath.resolve("icudtl.dat"))) {
                settings.resources_dir_path = libPath.toString();
                settings.locales_dir_path = libPath.resolve("locales").toString();
                System.out.println("*** JCEF: Using resources from " + libPath + " ***");
            }
        } else if (Environment.isWindows()) {
            // On Windows, resources are typically in bin/
            Path binPath = javaHomePath.resolve("bin");
            if (Files.exists(binPath.resolve("icudtl.dat"))) {
                settings.resources_dir_path = binPath.toString();
                settings.locales_dir_path = binPath.resolve("locales").toString();
                System.out.println("*** JCEF: Using resources from " + binPath + " ***");
            }
        }
    }
}
