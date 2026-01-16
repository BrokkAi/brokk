package ai.brokk.gui.mop.webview.cef;

import org.cef.CefApp;
import org.cef.handler.CefAppHandlerAdapter;
import org.jetbrains.annotations.Nullable;

/**
 * Abstraction for CEF initialization strategies.
 *
 * <p>Implementations provide different ways to initialize CEF:
 * <ul>
 *   <li>{@link JbrCefProvider} - Uses JBR's bundled JCEF (production/jDeploy)</li>
 *   <li>{@link MavenCefProvider} - Uses jcefmaven library (development)</li>
 * </ul>
 */
public interface CefAppProvider {

    /**
     * Creates and returns a configured CefApp instance.
     *
     * @param stateHandler optional handler for CefApp state changes
     * @return the initialized CefApp instance
     */
    CefApp createCefApp(@Nullable CefAppHandlerAdapter stateHandler);

    /**
     * Checks if this provider is available in the current environment.
     *
     * @return true if this provider can be used
     */
    boolean isAvailable();
}
