package ai.brokk.openai;

import ai.brokk.Service;
import ai.brokk.executor.http.SimpleHttpServer;
import ai.brokk.project.MainProject;
import ai.brokk.util.Environment;
import com.google.common.base.Splitter;
import com.sun.net.httpserver.HttpExchange;
import java.awt.Component;
import java.awt.Window;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Service for initiating OpenAI OAuth authorization flow.
 * Builds the authorization URL with PKCE, starts a local callback server,
 * and opens the authorization URL in the user's browser.
 */
public class OpenAiOAuthService {
    private static final Logger logger = LogManager.getLogger(OpenAiOAuthService.class);

    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String ISSUER = "https://auth.openai.com";
    private static final int OAUTH_PORT = 1455;
    private static final String REDIRECT_URI = "http://localhost:" + OAUTH_PORT + "/auth/callback";
    private static final String SCOPE = "openid profile email offline_access";

    private static final String PKCE_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
    private static final int VERIFIER_LENGTH = 43;
    private static final int STATE_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final Object lock = new Object();

    @Nullable
    private static SimpleHttpServer activeServer;

    @Nullable
    private static String pendingState;

    @Nullable
    private static String pendingVerifier;

    private static final String HTML_SUCCESS =
            """
            <!doctype html>
            <html>
              <head>
                <title>Brokk - OpenAI Authorization Successful</title>
                <style>
                  body {
                    font-family: system-ui, -apple-system, sans-serif;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    height: 100vh;
                    margin: 0;
                    background: #131010;
                    color: #f1ecec;
                  }
                  .container {
                    text-align: center;
                    padding: 2rem;
                  }
                  h1 {
                    color: #f1ecec;
                    margin-bottom: 1rem;
                  }
                  p {
                    color: #b7b1b1;
                  }
                </style>
              </head>
              <body>
                <div class="container">
                  <h1>Authorization Successful</h1>
                  <p>You can close this window and return to Brokk.</p>
                </div>
                <script>
                  setTimeout(() => window.close(), 2000)
                </script>
              </body>
            </html>
            """;

    private static String htmlError(String error) {
        return """
                <!doctype html>
                <html>
                  <head>
                    <title>Brokk - OpenAI Authorization Failed</title>
                    <style>
                      body {
                        font-family: system-ui, -apple-system, sans-serif;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        height: 100vh;
                        margin: 0;
                        background: #131010;
                        color: #f1ecec;
                      }
                      .container {
                        text-align: center;
                        padding: 2rem;
                      }
                      h1 {
                        color: #fc533a;
                        margin-bottom: 1rem;
                      }
                      p {
                        color: #b7b1b1;
                      }
                      .error {
                        color: #ff917b;
                        font-family: monospace;
                        margin-top: 1rem;
                        padding: 1rem;
                        background: #3c140d;
                        border-radius: 0.5rem;
                      }
                    </style>
                  </head>
                  <body>
                    <div class="container">
                      <h1>Authorization Failed</h1>
                      <p>An error occurred during authorization.</p>
                      <div class="error">%s</div>
                    </div>
                  </body>
                </html>
                """
                .formatted(escapeHtml(error));
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Initiates the OpenAI OAuth authorization flow by starting the callback server
     * and opening the authorization URL in the browser.
     *
     * @param parent The parent component for error dialogs
     */
    public static void startAuthorization(Component parent) {
        Window ancestor = (parent instanceof Window w) ? w : SwingUtilities.getWindowAncestor(parent);

        synchronized (lock) {
            stopActiveServerInternal();

            String verifier = generateVerifier();
            String challenge = generateChallenge(verifier);
            String state = generateState();
            pendingState = state;
            pendingVerifier = verifier;

            try {
                activeServer = new SimpleHttpServer("localhost", OAUTH_PORT, "", 1);
                activeServer.registerUnauthenticatedContext("/auth/callback", exchange -> handleCallback(exchange));
                activeServer.start();
                logger.info("OAuth callback server started on port {}", OAUTH_PORT);
            } catch (IOException e) {
                logger.error("Failed to start OAuth callback server on port {}", OAUTH_PORT, e);
                pendingState = null;
                pendingVerifier = null;
                SwingUtilities.invokeLater(() -> {
                    javax.swing.JOptionPane.showMessageDialog(
                            ancestor,
                            "Failed to start OAuth callback server on port " + OAUTH_PORT + ".\n"
                                    + "Please ensure no other application is using this port.",
                            "OAuth Server Error",
                            javax.swing.JOptionPane.ERROR_MESSAGE);
                });
                return;
            }

            String url = buildAuthorizationUrl(challenge, state);
            logger.debug("Opening OpenAI authorization URL");
            Environment.openInBrowser(url, ancestor);
        }
    }

    private static void handleCallback(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = parseQueryParams(query);

        String error = params.get("error");
        String errorDescription = params.get("error_description");
        String code = params.get("code");
        String state = params.get("state");
        String scope = params.get("scope");

        if (error != null) {
            String errorMsg = errorDescription != null ? errorDescription : error;
            logger.warn("OAuth callback received error: {}", errorMsg);
            sendHtmlResponseAndStopServer(exchange, 200, htmlError(errorMsg));
            return;
        }

        if (code == null) {
            String errorMsg = "Missing authorization code";
            logger.warn("OAuth callback missing code parameter");
            sendHtmlResponseAndStopServer(exchange, 400, htmlError(errorMsg));
            return;
        }

        String expectedState;
        String verifier;
        synchronized (lock) {
            expectedState = pendingState;
            verifier = pendingVerifier;
        }

        if (expectedState == null || !expectedState.equals(state)) {
            String errorMsg = "Invalid state - potential CSRF attack";
            logger.warn("OAuth callback state mismatch: expected={}, received={}", expectedState, state);
            sendHtmlResponseAndStopServer(exchange, 400, htmlError(errorMsg));
            return;
        }

        if (verifier == null) {
            String errorMsg = "Missing PKCE verifier - authorization state corrupted";
            logger.error("OAuth callback missing pendingVerifier");
            sendHtmlResponseAndStopServer(exchange, 500, htmlError(errorMsg));
            return;
        }

        logger.info(
                "OAuth callback received successfully: code={}, state={}, scope={}",
                code.substring(0, Math.min(8, code.length())) + "...",
                state,
                scope);

        String backendError = Service.forwardCodexOauthCallbackToBackend(params, verifier);
        if (backendError != null) {
            sendHtmlResponseAndStopServer(exchange, 200, htmlError(backendError));
        } else {
            MainProject.setOpenAiCodexOauthConnected(true);
            sendHtmlResponseAndStopServer(exchange, 200, HTML_SUCCESS);
        }
    }

    private static void sendHtmlResponseAndStopServer(HttpExchange exchange, int statusCode, String html) {
        try {
            byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(statusCode, htmlBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(htmlBytes);
            }
            exchange.close();
        } catch (IOException e) {
            logger.error("Failed to send HTML response", e);
        }

        Thread stopThread = new Thread(
                () -> {
                    synchronized (lock) {
                        stopActiveServerInternal();
                    }
                },
                "OAuth-Server-Stop");
        stopThread.setDaemon(true);
        stopThread.start();
    }

    private static void stopActiveServerInternal() {
        if (activeServer != null) {
            activeServer.stop(0);
            logger.info("OAuth callback server stopped");
            activeServer = null;
        }
        pendingState = null;
        pendingVerifier = null;
    }

    private static String buildAuthorizationUrl(String challenge, String state) {
        var params = new StringBuilder();
        params.append("response_type=").append(encode("code"));
        params.append("&client_id=").append(encode(CLIENT_ID));
        params.append("&redirect_uri=").append(encode(REDIRECT_URI));
        params.append("&scope=").append(encode(SCOPE));
        params.append("&code_challenge=").append(encode(challenge));
        params.append("&code_challenge_method=").append(encode("S256"));
        params.append("&id_token_add_organizations=").append(encode("true"));
        params.append("&codex_cli_simplified_flow=").append(encode("true"));
        params.append("&state=").append(encode(state));
        params.append("&originator=").append(encode("opencode"));

        return ISSUER + "/oauth/authorize?" + params;
    }

    private static String generateVerifier() {
        var sb = new StringBuilder(VERIFIER_LENGTH);
        for (int i = 0; i < VERIFIER_LENGTH; i++) {
            int idx = SECURE_RANDOM.nextInt(PKCE_CHARSET.length());
            sb.append(PKCE_CHARSET.charAt(idx));
        }
        return sb.toString();
    }

    private static String generateChallenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String generateState() {
        byte[] bytes = new byte[STATE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQueryParams(@Nullable String query) {
        var params = new HashMap<String, String>();
        if (query == null || query.isBlank()) {
            return params;
        }

        for (var pair : Splitter.on('&').split(query)) {
            var keyValue = pair.split("=", 2);
            var rawKey = keyValue[0];
            var rawValue = keyValue.length > 1 ? keyValue[1] : "";

            String key;
            String value;
            try {
                key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                key = rawKey;
            }
            try {
                value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                value = rawValue;
            }

            params.put(key, value);
        }
        return params;
    }
}
