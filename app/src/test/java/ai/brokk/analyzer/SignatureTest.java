package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SignatureTest {

    @Test
    void parse_WithNull_ReturnsNONE() {
        var sig = Signature.parse(null);
        assertSame(Signature.NONE, sig);
    }

    @Test
    void parse_WithEmptyString_ReturnsNONE() {
        var sig = Signature.parse("");
        assertSame(Signature.NONE, sig);
    }

    @Test
    void parse_WithValidSignature_ReturnsSignature() {
        var sig = Signature.parse("(int, String)");
        assertNotNull(sig);
        assertEquals("(int, String)", sig.value());
        assertNotSame(Signature.NONE, sig);
    }

    @Test
    void toNullableString_ForNONE_ReturnsNull() {
        assertNull(Signature.NONE.toNullableString());
    }

    @Test
    void toNullableString_ForValidSignature_ReturnsValue() {
        var sig = new Signature("(String, int)");
        assertEquals("(String, int)", sig.toNullableString());
    }

    @Test
    void equality_NONE_EqualsEmptySignature() {
        var empty = new Signature("");
        assertEquals(Signature.NONE, empty);
        assertEquals(Signature.NONE.hashCode(), empty.hashCode());
    }
}
