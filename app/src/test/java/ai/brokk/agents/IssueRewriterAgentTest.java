package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IssueRewriterAgentTest {

    @Test
    void testParseIssueResponse_ValidJson() {
        String json =
                """
                { "title": "Bug: NPE in Foo", "body": "Steps...\\n1) Do X\\n2) Do Y" }
                """;

        var response = IssueRewriterAgent.parseIssueResponse(json);

        assertNotNull(response);
        assertEquals("Bug: NPE in Foo", response.title());
        assertEquals("Steps...\n1) Do X\n2) Do Y", response.bodyMarkdown());
    }

    @Test
    void testParseIssueResponse_MissingFields_ThrowsException() {
        String onlyTitle = """
                { "title": "Bug: NPE in Foo" }
                """;
        String onlyBody = """
                { "body": "Steps..." }
                """;

        assertThrows(IllegalArgumentException.class, () -> IssueRewriterAgent.parseIssueResponse(onlyTitle));
        assertThrows(IllegalArgumentException.class, () -> IssueRewriterAgent.parseIssueResponse(onlyBody));
    }

    @Test
    void testParseIssueResponse_MalformedJson_ThrowsException() {
        String malformed = "{ this is not valid json }";
        assertThrows(IllegalArgumentException.class, () -> IssueRewriterAgent.parseIssueResponse(malformed));
    }

    @Test
    void testParseIssueResponse_EmptyInput_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> IssueRewriterAgent.parseIssueResponse(""));
        assertThrows(IllegalArgumentException.class, () -> IssueRewriterAgent.parseIssueResponse("   "));
    }
}
