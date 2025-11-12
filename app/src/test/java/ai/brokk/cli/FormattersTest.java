package ai.brokk.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FormattersTest {

    @Test
    void balanceFormatter_basicCases() {
        assertEquals("$0.00", BalanceFormatter.format(0f));
        assertEquals("$1.20", BalanceFormatter.format(1.2f));
        assertEquals("$1.23", BalanceFormatter.format(1.234f));
        assertEquals("$0.00", BalanceFormatter.format(-1f));
        assertEquals("$0.00", BalanceFormatter.format(Float.NaN));
    }

    @Test
    void tokenUsageFormatter_compactSummary() {
        var s1 = TokenUsageFormatter.format(100, 10, 0, 50);
        assertTrue(s1.contains("in 100"));
        assertTrue(s1.contains("cached 10"));
        assertTrue(s1.contains("think 0"));
        assertTrue(s1.contains("out 50"));
        assertTrue(s1.contains("total 160"));

        var s2 = TokenUsageFormatter.format(0, 0, 0, 0);
        assertTrue(s2.contains("total 0"));

        var s3 = TokenUsageFormatter.format(-5, -2, -3, -4);
        assertTrue(s3.contains("total 0"));
    }
}
