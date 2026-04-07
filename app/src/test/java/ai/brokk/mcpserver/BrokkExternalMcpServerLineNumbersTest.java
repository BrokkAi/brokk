package ai.brokk.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BrokkExternalMcpServerLineNumbersTest {

    @Test
    void withLineNumbers_numbersLfLines() {
        String input = "alpha\nbeta\ngamma";

        String result = BrokkExternalMcpServer.withLineNumbers(input);

        assertEquals(
                """
                1: alpha
                2: beta
                3: gamma
                """
                        .stripTrailing(),
                result);
    }

    @Test
    void withLineNumbers_handlesCrLfAndTrailingNewlineWithoutPhantomLine() {
        String input = "first\r\nsecond\r\n";

        String result = BrokkExternalMcpServer.withLineNumbers(input);

        assertEquals(
                """
                1: first
                2: second
                """.stripTrailing(), result);
    }

    @Test
    void withLineNumbers_emptyInputReturnsEmpty() {
        assertEquals("", BrokkExternalMcpServer.withLineNumbers(""));
    }
}
