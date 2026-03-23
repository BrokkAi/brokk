package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServiceAuthValidationTest {
    @Test
    void validateBrokkAuth_nullKey_returnsMissingKey() {
        var result = Service.validateBrokkAuth(null);

        assertEquals(BrokkAuthValidation.State.MISSING_KEY, result.state());
        assertFalse(result.valid());
        assertFalse(result.hasBalance());
    }

    @Test
    void validateBrokkAuth_blankKey_returnsMissingKey() {
        var result = Service.validateBrokkAuth("");

        assertEquals(BrokkAuthValidation.State.MISSING_KEY, result.state());
        assertFalse(result.valid());
        assertFalse(result.hasBalance());
    }

    @Test
    void validateBrokkAuth_malformedKey_returnsInvalidFormat() {
        var result = Service.validateBrokkAuth("not-a-brokk-key");

        assertEquals(BrokkAuthValidation.State.INVALID_KEY_FORMAT, result.state());
        assertFalse(result.valid());
        assertTrue(result.message().contains("format"));
    }
}
