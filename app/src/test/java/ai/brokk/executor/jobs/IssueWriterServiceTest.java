package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class IssueWriterServiceTest {

    @Test
    void testParseIssueResponse_ValidJson() {
        String json =
                """
                { "title": "Bug: NPE in Foo", "bodyMarkdown": "Steps...\\n1) Do X\\n2) Do Y" }
                """;

        var response = IssueWriterService.parseIssueResponse(json);

        assertNotNull(response);
        assertEquals("Bug: NPE in Foo", response.title());
        assertEquals("Steps...\n1) Do X\n2) Do Y", response.bodyMarkdown());
    }

    @Test
    void testParseIssueResponse_WrappedJson() {
        String wrapped =
                """
                Here is the issue:

                ```json
                {
                  "title": "Bug: NPE in Foo",
                  "bodyMarkdown": "Steps...\\n- a\\n- b"
                }
                ```

                End.
                """;

        var response = IssueWriterService.parseIssueResponse(wrapped);

        assertNotNull(response);
        assertEquals("Bug: NPE in Foo", response.title());
        assertEquals("Steps...\n- a\n- b", response.bodyMarkdown());
    }

    @Test
    void testParseIssueResponse_MissingFields_ReturnsNull() {
        String onlyTitle =
                """
                { "title": "Bug: NPE in Foo" }
                """;
        String onlyBody =
                """
                { "bodyMarkdown": "Steps..." }
                """;

        assertNull(IssueWriterService.parseIssueResponse(onlyTitle));
        assertNull(IssueWriterService.parseIssueResponse(onlyBody));
    }

    @Test
    void testParseIssueResponse_MalformedJson_ReturnsNull() {
        String malformed = "{ this is not valid json }";
        assertNull(IssueWriterService.parseIssueResponse(malformed));
    }

    @Test
    void testParseIssueResponse_WrongTypes_ReturnsNull() {
        String wrongTypes =
                """
                { "title": 123, "bodyMarkdown": true }
                """;
        assertNull(IssueWriterService.parseIssueResponse(wrongTypes));
    }

    @Test
    void testParseIssueResponse_EmptyInput_ReturnsNull() {
        assertNull(IssueWriterService.parseIssueResponse(""));
        assertNull(IssueWriterService.parseIssueResponse("   "));
        assertNull(IssueWriterService.parseIssueResponse(null));
    }
}
