package ai.brokk.executor.manager.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TokenServiceTest {

    @Test
    void testMintAndValidate() throws TokenService.InvalidTokenException {
        var service = new TokenService("test-secret-key");
        var sessionId = UUID.randomUUID();
        var validity = Duration.ofHours(1);

        var token = service.mint(sessionId, validity);
        assertNotNull(token);
        assertTrue(token.contains("."));

        var sessionToken = service.validate(token);
        assertEquals(sessionId, sessionToken.sessionId());
        assertFalse(sessionToken.isExpired());
    }

    @Test
    void testValidateExpiredToken() {
        var service = new TokenService("test-secret-key");
        var sessionId = UUID.randomUUID();
        var validity = Duration.ofMillis(1);

        var token = service.mint(sessionId, validity);

        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        var exception = assertThrows(TokenService.InvalidTokenException.class, () -> service.validate(token));
        assertTrue(exception.getMessage().contains("expired"));
    }

    @Test
    void testValidateInvalidSignature() {
        var service1 = new TokenService("secret-key-1");
        var service2 = new TokenService("secret-key-2");

        var sessionId = UUID.randomUUID();
        var token = service1.mint(sessionId, Duration.ofHours(1));

        var exception = assertThrows(TokenService.InvalidTokenException.class, () -> service2.validate(token));
        assertEquals("Invalid signature", exception.getMessage());
    }

    @Test
    void testValidateMalformedToken() {
        var service = new TokenService("test-secret-key");

        assertThrows(TokenService.InvalidTokenException.class, () -> service.validate(""));
        assertThrows(TokenService.InvalidTokenException.class, () -> service.validate("not-a-token"));
        assertThrows(TokenService.InvalidTokenException.class, () -> service.validate("only.one.dot"));
    }

    @Test
    void testValidateInvalidBase64() {
        var service = new TokenService("test-secret-key");
        var invalidToken = "!!!invalid!!!.base64";

        var exception = assertThrows(TokenService.InvalidTokenException.class, () -> service.validate(invalidToken));
        assertTrue(exception.getMessage().contains("base64"));
    }

    @Test
    void testSessionScope() throws TokenService.InvalidTokenException {
        var service = new TokenService("test-secret-key");
        var sessionId1 = UUID.randomUUID();
        var sessionId2 = UUID.randomUUID();

        var token1 = service.mint(sessionId1, Duration.ofHours(1));
        var token2 = service.mint(sessionId2, Duration.ofHours(1));

        var sessionToken1 = service.validate(token1);
        var sessionToken2 = service.validate(token2);

        assertEquals(sessionId1, sessionToken1.sessionId());
        assertEquals(sessionId2, sessionToken2.sessionId());
        assertNotEquals(token1, token2);
    }

    @Test
    void testConstructorRejectsBlankSecret() {
        assertThrows(IllegalArgumentException.class, () -> new TokenService(""));
        assertThrows(IllegalArgumentException.class, () -> new TokenService("   "));
    }
}
