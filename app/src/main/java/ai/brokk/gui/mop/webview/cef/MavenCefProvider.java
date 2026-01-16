package ai.brokk.gui.mop.webview.cef;

import java.nio.file.Path;
import java.nio.file.Paths;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler;
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
 *   <li>Not running in jDeploy (no {@code jdeploy.app.path} property)</li>
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
     * Checks if jcefmaven classes are available.
     *
     * <p>Uses reflection to avoid ClassNotFoundException when jcefmaven
     * is stripped from production builds.
     */
    @Override
    public boolean isAvailable() {
        try {
            Class.forName("me.friwi.jcefmaven.CefAppBuilder");
            logger.info("Maven CEF provider is available");
            return true;
        } catch (ClassNotFoundException e) {
            logger.debug("jcefmaven not available (classes stripped or not on classpath)");
            return false;
        }
    }

    @Override
    public CefApp createCefApp(@Nullable CefAppHandlerAdapter stateHandler) {
        logger.info("MavenCefProvider.createCefApp() called");

        Path jcefDir = getJcefDir();
        var builder = new CefAppBuilder();
        builder.setInstallDir(jcefDir.toFile());
        builder.setProgressHandler(new ConsoleProgressHandler());

        CefSettings settings = builder.getCefSettings();
        settings.windowless_rendering_enabled = false;
        // Dark background to reduce flash while loading
        settings.background_color = settings.new ColorType(0xFF, 37, 37, 37);

        // Set cache path to avoid conflicts with JBR provider
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            Path cacheDir = Paths.get(userHome, ".brokk", "cef-cache", "maven");
            settings.cache_path = cacheDir.toString();
            logger.info("Set CEF cache path to: {}", cacheDir);
        }

        // Wrap the state handler if provided
        builder.setAppHandler(new MavenCefAppHandlerAdapter() {
            @Override
            public void stateHasChanged(CefApp.CefAppState state) {
                logger.info("CefApp state changed: {}", state);
                if (stateHandler != null) {
                    stateHandler.stateHasChanged(state);
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
     */
    private Path getJcefDir() {
        // Development mode - use local jcef-bundle directory
        return Paths.get("./jcef-bundle");
    }
}
