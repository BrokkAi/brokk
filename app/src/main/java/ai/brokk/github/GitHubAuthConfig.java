package ai.brokk.github;

/**
 * Configuration for GitHub OAuth authentication using Device Flow.
 *
 * <p>SECURITY NOTE: The client ID embedded here is safe to be public. - GitHub client IDs are designed to be public
 * information - Device Flow does not require client secrets (which would be sensitive) - This follows GitHub's best
 * practices for public clients (desktop/CLI apps) - The client ID can be overridden via BROKK_GITHUB_CLIENT_ID
 * environment variable
 */
public class GitHubAuthConfig {
    /**
     * Default GitHub OAuth app client ID for Brokk. This is safe to embed in source code as client IDs are public by
     * design.
     */
    private static final String DEFAULT_CLIENT_ID = "Iv23liZ3oStCdzu0xkHI";

    /**
     * Environment variable to override the default client ID. Useful for enterprise deployments or custom GitHub Apps.
     */
    private static final String ENV_VAR_NAME = "BROKK_GITHUB_CLIENT_ID";

    public static String getClientId() {
        var clientId = System.getenv(ENV_VAR_NAME);
        if (clientId != null && !clientId.isBlank()) {
            return clientId;
        }
        return DEFAULT_CLIENT_ID;
    }
}
