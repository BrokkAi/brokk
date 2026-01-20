package ai.brokk.gui.mop.webview.cef;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Factory for selecting the appropriate CEF provider.
 *
 * <p>Selection priority:
 * <ol>
 *   <li>{@link JbrCefProvider} - Production jDeploy builds with JBR+JCEF</li>
 *   <li>{@link MavenCefProvider} - Development with jcefmaven</li>
 * </ol>
 *
 * <p>This design allows the application to work in both production
 * (where jcefmaven is stripped) and development (where JBR may not be present).
 */
public final class CefAppProviderFactory {

    private static final Logger logger = LogManager.getLogger(CefAppProviderFactory.class);

    private CefAppProviderFactory() {
        // Utility class
    }

    /**
     * Returns the name of the CEF provider that will be used.
     * Useful for logging/banner display before actual provider initialization.
     *
     * @return "JBR" for production jDeploy builds, "Maven" for development, or "none" if unavailable
     */
    public static String getProviderName() {
        if (new JbrCefProvider().isAvailable()) {
            return "JBR";
        }
        try {
            if (new MavenCefProvider().isAvailable()) {
                return "Maven";
            }
            System.out.println("[CEF DEBUG] MavenCefProvider.isAvailable() returned false");
        } catch (NoClassDefFoundError e) {
            System.out.println("[CEF DEBUG] MavenCefProvider dependencies not available: " + e.getMessage());
        }
        return "none";
    }

    /**
     * Returns the appropriate CEF provider for the current environment.
     *
     * @return a CEF provider that is available in this environment
     * @throws IllegalStateException if no provider is available
     */
    public static CefAppProvider getProvider() {
        // Priority 1: JBR (production jDeploy builds)
        CefAppProvider jbr = new JbrCefProvider();
        if (jbr.isAvailable()) {
            logger.info("Using JBR CEF provider (production mode)");
            return jbr;
        }

        // Priority 2: Maven (development)
        // Catch NoClassDefFoundError in case loading MavenCefProvider eagerly resolves
        // me.friwi.* references that are stripped in production builds
        try {
            CefAppProvider maven = new MavenCefProvider();
            if (maven.isAvailable()) {
                logger.info("Using Maven CEF provider (development mode)");
                return maven;
            }
        } catch (NoClassDefFoundError e) {
            logger.debug("MavenCefProvider dependencies not available: {}", e.getMessage());
        }

        // No provider available
        throw new IllegalStateException("No CEF provider available. "
                + "Either run with jDeploy (JBR+JCEF) or ensure jcefmaven is on the classpath.");
    }
}
