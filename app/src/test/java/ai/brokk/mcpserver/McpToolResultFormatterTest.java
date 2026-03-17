package ai.brokk.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.tools.ToolExecutionResult;
import org.junit.jupiter.api.Test;

class McpToolResultFormatterTest {

    @Test
    void numberFencedCodeBlocks_numbersFencedBlocksIndependently() {
        String input =
                """
                Here is some code:
                ```java
                public class Foo {
                    void bar() {}
                }
                ```
                And another one:
                ```python
                def baz():
                    pass
                ```
                Fin.""";

        String expected =
                """
                Here is some code:
                ```java
                   1 | public class Foo {
                   2 |     void bar() {}
                   3 | }
                ```
                And another one:
                ```python
                   1 | def baz():
                   2 |     pass
                ```
                Fin.""";

        assertEquals(expected, BrokkExternalMcpServer.numberFencedCodeBlocks(input));
    }

    @Test
    void numberFencedCodeBlocks_preservesProse() {
        String input = "Just some text without fences.";
        assertEquals(input, BrokkExternalMcpServer.numberFencedCodeBlocks(input));
    }

    @Test
    void numberFencedCodeBlocks_ignoresMalformedOrUnclosedFences() {
        String unclosed = """
                ```java
                public class Foo {}
                """;
        assertEquals(unclosed, BrokkExternalMcpServer.numberFencedCodeBlocks(unclosed));

        String oddCount = """
                ```java
                one
                ```
                ```python
                two
                """;
        assertEquals(oddCount, BrokkExternalMcpServer.numberFencedCodeBlocks(oddCount));
    }

    @Test
    void numberFencedCodeBlocks_handlesEmptyFence() {
        String input = "```\n```";
        assertEquals(input, BrokkExternalMcpServer.numberFencedCodeBlocks(input));
    }

    @Test
    void formatMcpSourceRetrievalResponse_gatesByToolAndStatus() {
        String code = "Prose before\n```java\nfoo\n```\nProse after";
        String numbered = "Prose before\n```java\n   1 | foo\n```\nProse after";

        // Success + getClassSources -> numbered
        assertEquals(
                numbered,
                BrokkExternalMcpServer.formatMcpSourceRetrievalResponse(
                        "getClassSources", ToolExecutionResult.Status.SUCCESS, code));

        // Success + getMethodSources -> numbered
        assertEquals(
                numbered,
                BrokkExternalMcpServer.formatMcpSourceRetrievalResponse(
                        "getMethodSources", ToolExecutionResult.Status.SUCCESS, code));

        // Error + target tool -> original
        assertEquals(
                code,
                BrokkExternalMcpServer.formatMcpSourceRetrievalResponse(
                        "getClassSources", ToolExecutionResult.Status.INTERNAL_ERROR, code));

        // Success + non-target tool -> original
        assertEquals(
                code,
                BrokkExternalMcpServer.formatMcpSourceRetrievalResponse(
                        "getFileContents", ToolExecutionResult.Status.SUCCESS, code));
    }

    @Test
    void formatMcpSourceRetrievalResponse_unclosedFenceRemainsUnchanged() {
        String unclosed = "```java\npublic class Foo {\n";
        assertEquals(
                unclosed,
                BrokkExternalMcpServer.formatMcpSourceRetrievalResponse(
                        "getClassSources", ToolExecutionResult.Status.SUCCESS, unclosed));
    }
}
