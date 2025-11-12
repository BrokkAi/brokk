package ai.brokk.executor.manager.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Service for minting and validating session-scoped HMAC-SHA256 signed tokens.
 * Token format: base64(payload).base64(signature)
 * Payload: {"sessionId":"uuid","issuedAt":epochMillis,"expiresAt":epochMillis}
 */
public final class TokenService {
    private static final Logger logger = LogManager.getLogger(TokenService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final byte[] secretKey;

    public TokenService(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Token secret must not be blank");
        }
        this.secretKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Mint a new session-scoped token.
     *
     * @param sessionId the session ID to encode in the token
     * @param validity how long the token should be valid
     * @return the signed token string
     */
    public String mint(UUID sessionId, Duration validity) {
        var issuedAt = Instant.now();
        var expiresAt = issuedAt.plus(validity);

        var payload = Map.of(
                "sessionId", sessionId.toString(),
                "issuedAt", issuedAt.toEpochMilli(),
                "expiresAt", expiresAt.toEpochMilli());

        try {
            var payloadJson = objectMapper.writeValueAsString(payload);
            var payloadBytes = payloadJson.getBytes(StandardCharsets.UTF_8);
            var payloadBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes);

            var signature = computeSignature(payloadBytes);
            var signatureBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

            return payloadBase64 + "." + signatureBase64;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to mint token", e);
        }
    }

    /**
     * Validate and parse a token.
     *
     * @param token the token string to validate
     * @return the validated session token
     * @throws InvalidTokenException if the token is invalid, expired, or malformed
     */
    public SessionToken validate(String token) throws InvalidTokenException {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("Token is blank");
        }

        var parts = token.split("\\.", 2);
        if (parts.length != 2) {
            throw new InvalidTokenException("Invalid token format");
        }

        var payloadBase64 = parts[0];
        var signatureBase64 = parts[1];

        byte[] payloadBytes;
        byte[] providedSignature;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(payloadBase64);
            providedSignature = Base64.getUrlDecoder().decode(signatureBase64);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("Invalid base64 encoding", e);
        }

        var expectedSignature = computeSignature(payloadBytes);
        if (!constantTimeEquals(expectedSignature, providedSignature)) {
            throw new InvalidTokenException("Invalid signature");
        }

        try {
            var payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            var payload = objectMapper.readValue(payloadJson, Map.class);

            var sessionIdStr = (String) payload.get("sessionId");
            var issuedAtMillis = ((Number) payload.get("issuedAt")).longValue();
            var expiresAtMillis = ((Number) payload.get("expiresAt")).longValue();

            var sessionId = UUID.fromString(sessionIdStr);
            var issuedAt = Instant.ofEpochMilli(issuedAtMillis);
            var expiresAt = Instant.ofEpochMilli(expiresAtMillis);

            var sessionToken = new SessionToken(sessionId, issuedAt, expiresAt);

            if (sessionToken.isExpired()) {
                throw new InvalidTokenException("Token expired at " + expiresAt);
            }

            return sessionToken;
        } catch (InvalidTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidTokenException("Failed to parse token payload", e);
        }
    }

    private byte[] computeSignature(byte[] data) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            var keySpec = new SecretKeySpec(secretKey, HMAC_ALGORITHM);
            mac.init(keySpec);
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute HMAC signature", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    public static final class InvalidTokenException extends Exception {
        public InvalidTokenException(String message) {
            super(message);
        }

        public InvalidTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
