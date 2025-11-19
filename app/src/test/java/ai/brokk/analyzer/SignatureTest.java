package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SignatureTest {

    @Test
    void parse_WithNull_ReturnsNone() {
        var sig = Signature.parse(null);
        assertSame(Signature.none(), sig);
    }

    @Test
    void parse_WithEmptyString_ReturnsNone() {
        var sig = Signature.parse("");
        assertSame(Signature.none(), sig);
    }

    @Test
    void parse_WithValidSignature_ReturnsParameters() {
        var sig = Signature.parse("(int, String)");
        assertNotNull(sig);
        assertEquals("(int, String)", sig.value());
        assertNotSame(Signature.none(), sig);
        assertInstanceOf(Signature.Parameters.class, sig);
    }

    @Test
    void toNullableString_ForNone_ReturnsNull() {
        assertNull(Signature.none().toNullableString());
    }

    @Test
    void toNullableString_ForParameters_ReturnsValue() {
        var sig = Signature.of("(String, int)");
        assertEquals("(String, int)", sig.toNullableString());
    }

    @Test
    void none_ReturnsSingletonInstance() {
        var none1 = Signature.none();
        var none2 = Signature.none();
        assertSame(none1, none2);
        assertInstanceOf(Signature.None.class, none1);
    }

    @Test
    void of_WithValidSignature_ReturnsParameters() {
        var sig = Signature.of("(int, double)");
        assertInstanceOf(Signature.Parameters.class, sig);
        assertEquals("(int, double)", sig.value());
    }

    @Test
    void of_WithNull_ThrowsException() {
        var ex = assertThrows(IllegalArgumentException.class, () -> Signature.of(null));
        assertTrue(ex.getMessage().contains("Use Signature.none()"));
    }

    @Test
    void of_WithEmptyString_ThrowsException() {
        var ex = assertThrows(IllegalArgumentException.class, () -> Signature.of(""));
        assertTrue(ex.getMessage().contains("Use Signature.none()"));
    }

    @Test
    void of_WithoutParentheses_ThrowsException() {
        var ex = assertThrows(IllegalArgumentException.class, () -> Signature.of("int, String"));
        assertTrue(ex.getMessage().contains("Invalid signature format"));
    }

    @Test
    void of_WithMissingOpenParen_ThrowsException() {
        var ex = assertThrows(IllegalArgumentException.class, () -> Signature.of("int, String)"));
        assertTrue(ex.getMessage().contains("Invalid signature format"));
    }

    @Test
    void of_WithMissingCloseParen_ThrowsException() {
        var ex = assertThrows(IllegalArgumentException.class, () -> Signature.of("(int, String"));
        assertTrue(ex.getMessage().contains("Invalid signature format"));
    }

    @Test
    void isEmpty_ForNone_ReturnsTrue() {
        assertTrue(Signature.none().isEmpty());
    }

    @Test
    void isEmpty_ForParameters_ReturnsFalse() {
        assertFalse(Signature.of("()").isEmpty());
        assertFalse(Signature.of("(int)").isEmpty());
    }

    @Test
    void value_ForNone_ReturnsEmptyString() {
        assertEquals("", Signature.none().value());
    }

    @Test
    void value_ForParameters_ReturnsValue() {
        assertEquals("(String, int)", Signature.of("(String, int)").value());
    }

    @Test
    void patternMatching_WithNone() {
        Signature sig = Signature.none();
        String result =
                switch (sig) {
                    case Signature.None ignored -> "none";
                    case Signature.Parameters(var value) -> "params: " + value;
                };
        assertEquals("none", result);
    }

    @Test
    void patternMatching_WithParameters() {
        Signature sig = Signature.of("(int, String)");
        String result =
                switch (sig) {
                    case Signature.None ignored -> "none";
                    case Signature.Parameters(var value) -> "params: " + value;
                };
        assertEquals("params: (int, String)", result);
    }
}
