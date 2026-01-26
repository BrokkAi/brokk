package ai.brokk.gui.mop.webview.cef;

import ai.brokk.BuildInfo;
import ai.brokk.util.Environment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.CefApp;
import org.cef.CefSettings;
import org.cef.handler.CefAppHandlerAdapter;
import org.jetbrains.annotations.Nullable;

/**
 * CEF provider using jcefmaven library.
 *
 * <p>This provider is used in development when:
 * <ul>
 *   <li>Not running in jDeploy (no {@code jdeploy.launcher.path} property)</li>
 *   <li>jcefmaven classes are available on the classpath</li>
 * </ul>
 *
 * <p>jcefmaven automatically downloads and manages JCEF binaries,
 * making it ideal for development environments.
 *
 * <p>Note: In production jDeploy builds, jcefmaven classes are stripped
 * via .jdpignore, so {@link #isAvailable()} will return false.
 */
public class MavenCefProvider implements CefAppProvider {

    private static final Logger logger = LogManager.getLogger(MavenCefProvider.class);

    /**
     * Checks if jcefmaven can be used.
     *
     * <p>Returns false if:
     * <ul>
     *   <li>jcefmaven classes are not available (stripped in production)</li>
     *   <li>JBR's bundled JCEF is detected (would conflict with jcefmaven)</li>
     * </ul>
     */
    @Override
    public boolean isAvailable() {
        // Don't use jcefmaven if the JVM has bundled JCEF (e.g., JBR)
        // Using both would cause native library conflicts and segfaults
        if (hasJvmBundledJcef()) {
            logger.trace("JVM has bundled JCEF, Maven provider unavailable to avoid conflicts");
            return false;
        }

        try {
            Class.forName("me.friwi.jcefmaven.CefAppBuilder");
            logger.trace("Maven CEF provider is available");
            return true;
        } catch (ClassNotFoundException e) {
            logger.trace("jcefmaven not available (classes stripped or not on classpath): {}", e.getMessage());
            return false;
        }
    }

    /**
     * Checks if the JVM has bundled JCEF (e.g., JetBrains Runtime).
     */
    private static boolean hasJvmBundledJcef() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) return false;

        // Check for jcef_helper which indicates bundled JCEF
        if (Environment.isMacOs() || Environment.isLinux()) {
            return Files.exists(Paths.get(javaHome, "lib", "jcef_helper"));
        } else if (Environment.isWindows()) {
            return Files.exists(Paths.get(javaHome, "bin", "jcef_helper.exe"));
        }
        return false;
    }

    @Override
    public CefApp createCefApp(@Nullable CefAppHandlerAdapter stateHandler) {
        logger.info("MavenCefProvider.createCefApp() called");

        Path jcefDir = getJcefDir();
        var builder = new CefAppBuilder();
        builder.setInstallDir(jcefDir.toFile());
        // Use logger-based progress handler instead of ConsoleProgressHandler
        builder.setProgressHandler((state, percent) -> logger.debug("JCEF maven progress: {} ({}%)", state, percent));

        CefSettings settings = builder.getCefSettings();
        CefSettingsHelper.configureCommonSettings(settings);
        CefSettingsHelper.configureCachePath(settings, "maven", null);

        // Configure HiDPI scaling if needed
        String hiDpiArg = CefSettingsHelper.getHiDpiArg();
        if (hiDpiArg != null) {
            builder.addJcefArgs(hiDpiArg);
        }

        // Wrap the state handler if provided
        // Note: Wrapped in try-catch since JCEF callbacks bypass Java's uncaught exception handler
        builder.setAppHandler(new MavenCefAppHandlerAdapter() {
            @Override
            public void stateHasChanged(CefApp.CefAppState state) {
                try {
                    logger.info("CefApp state changed: {}", state);
                    if (stateHandler != null) {
                        stateHandler.stateHasChanged(state);
                    }
                } catch (Throwable t) {
                    logger.error("Exception in MavenCefProvider stateHasChanged callback", t);
                }
            }
        });

        try {
            logger.info("Building CefApp with jcefmaven (install dir: {})", jcefDir);
            CefApp app = builder.build();
            logger.info("CefApp created successfully");
            return app;
        } catch (Exception e) {
            logger.error("Failed to build CefApp with jcefmaven", e);
            throw new RuntimeException("Failed to initialize JCEF via jcefmaven", e);
        }
    }

    /**
     * Returns the directory where jcefmaven should install/find JCEF binaries.
     * Uses ~/.gradle/jcef-{version}/ to keep binaries alongside gradle cache
     * and avoid conflicts between different jcefmaven versions.
     */
    private Path getJcefDir() {
        String userHome = System.getProperty("user.home");
        Path dir = Paths.get(userHome, ".gradle", "caches", "jcef-" + BuildInfo.jcefmavenVersion);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            logger.warn("Failed to create JCEF directory: {}", dir, e);
        }
        return dir;
    }
}
