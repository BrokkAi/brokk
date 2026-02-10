package ai.brokk.gui.dialogs;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SettingsProjectBuildPanelTest {

    @Test
    void testValidateTimeoutNoTimeout() {
        var result = SettingsProjectBuildPanel.validateTimeout("No timeout");
        assertTrue(result.isValid());
        assertEquals(-1L, result.seconds());

        result = SettingsProjectBuildPanel.validateTimeout("");
        assertTrue(result.isValid());
        assertEquals(-1L, result.seconds());
    }

    @Test
    void testValidateTimeoutValidNumeric() {
        var result = SettingsProjectBuildPanel.validateTimeout("30");
        assertTrue(result.isValid());
        assertEquals(30L, result.seconds());

        result = SettingsProjectBuildPanel.validateTimeout(" 120 ");
        assertTrue(result.isValid());
        assertEquals(120L, result.seconds());
    }

    @Test
    void testValidateTimeoutInvalidWithSecSuffix() {
        // Now that unit is in the label, "sec" in the field is invalid
        var result = SettingsProjectBuildPanel.validateTimeout("60 sec");
        assertFalse(result.isValid());
        assertEquals("Please enter a valid numeric value for seconds.", result.errorMessage());
    }

    @Test
    void testValidateTimeoutInvalidNumericUnderMinimum() {
        var result = SettingsProjectBuildPanel.validateTimeout("29");
        assertFalse(result.isValid());
        assertEquals("Timeout must be at least 30 seconds.", result.errorMessage());
    }

    @Test
    void testValidateTimeoutNonNumeric() {
        var result = SettingsProjectBuildPanel.validateTimeout("abc");
        assertFalse(result.isValid());
        assertEquals("Please enter a valid numeric value for seconds.", result.errorMessage());
    }

    @Test
    void testValidateTimeoutNullOrEmpty() {
        var result = SettingsProjectBuildPanel.validateTimeout(null);
        assertTrue(result.isValid(), "Should fallback to default");

        // We don't check the exact value of default here as it depends on Environment
        assertTrue(result.seconds() > 0);
    }
}
