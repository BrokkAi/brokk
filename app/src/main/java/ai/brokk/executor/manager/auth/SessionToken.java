package ai.brokk.executor.manager.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * Validated session token claims.
 *
 * @param sessionId the session this token grants access to
 * @param issuedAt when the token was issued
 * @param expiresAt when the token expires
 */
public record SessionToken(UUID sessionId, Instant issuedAt, Instant expiresAt) {
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
