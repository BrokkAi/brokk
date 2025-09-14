package io.github.jbellis.brokk.github;

public class GitHubAuthConfig {
    private static final String DEFAULT_CLIENT_ID = "Iv23liZ3oStCdzu0xkHI";
    private static final String ENV_VAR_NAME = "BROKK_GITHUB_CLIENT_ID";

    public static String getClientId() {
        var clientId = System.getenv(ENV_VAR_NAME);
        if (clientId != null && !clientId.isBlank()) {
            return clientId;
        }
        return DEFAULT_CLIENT_ID;
    }
}