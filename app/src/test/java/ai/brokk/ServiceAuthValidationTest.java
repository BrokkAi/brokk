package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServiceAuthValidationTest {
    @Test
    void validateBrokkAuth_nullKey_returnsLocalNoAuthSuccess() {
        var result = Service.validateBrokkAuth(null);

        assertEquals(BrokkAuthValidation.State.FREE_USER, result.state());
        assertTrue(result.valid());
        assertTrue(result.message().contains("not required"));
    }

    @Test
    void validateBrokkAuth_blankKey_returnsLocalNoAuthSuccess() {
        var result = Service.validateBrokkAuth("");

        assertEquals(BrokkAuthValidation.State.FREE_USER, result.state());
        assertTrue(result.valid());
        assertTrue(result.message().contains("not required"));
    }

    @Test
    void validateBrokkAuth_malformedKey_returnsLocalNoAuthSuccess() {
        var result = Service.validateBrokkAuth("not-a-brokk-key");

        assertEquals(BrokkAuthValidation.State.FREE_USER, result.state());
        assertTrue(result.valid());
        assertTrue(result.message().contains("not required"));
    }
}
