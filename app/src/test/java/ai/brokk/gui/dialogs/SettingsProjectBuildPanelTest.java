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
        assertTrue(result.isValid(), "30 should be valid");
        assertEquals(30L, result.seconds());

        result = SettingsProjectBuildPanel.validateTimeout(" 120 ");
        assertTrue(result.isValid(), "Padded numeric should be valid");
        assertEquals(120L, result.seconds());
    }

    @Test
    void testValidateTimeoutInvalidWithSecSuffix() {
        // Now that unit is in the label, "sec" in the field is strictly invalid
        var result = SettingsProjectBuildPanel.validateTimeout("60 sec");
        assertFalse(result.isValid(), "'60 sec' should be invalid under strict numeric rules");
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
        assertTrue(result.isValid(), "Null input should fallback to default and be valid");
        // Null or unknown types fallback to Environment default
        assertTrue(result.seconds() > 0 || result.seconds() == -1);

        result = SettingsProjectBuildPanel.validateTimeout("");
        assertTrue(result.isValid(), "Empty string should map to No timeout (-1)");
        assertEquals(-1L, result.seconds());
    }
}
