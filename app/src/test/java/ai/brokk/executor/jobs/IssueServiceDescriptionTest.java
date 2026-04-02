package ai.brokk.executor.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class IssueServiceDescriptionTest {

    @Test
    void testBuildPrDescription_NullSummary() {
        String result = IssueService.buildPrDescription(null, 123);
        assertEquals("Fixes #123", result);
    }

    @Test
    void testBuildPrDescription_EmptySummary() {
        String result = IssueService.buildPrDescription("", 123);
        assertEquals("Fixes #123", result);
    }

    @Test
    void testBuildPrDescription_WhitespaceOnlySummary() {
        String result = IssueService.buildPrDescription("   \n\t  ", 123);
        assertEquals("Fixes #123", result);
    }

    @Test
    void testBuildPrDescription_TrimsSummary() {
        String result = IssueService.buildPrDescription("  A short summary  ", 123);
        assertEquals("A short summary\n\nFixes #123", result);
    }

    @Test
    void testBuildPrDescription_MultilineSummaryPreserved() {
        String summary = "First line\n\nDetailed description.\n";
        String result = IssueService.buildPrDescription(summary, 123);
        // trailing newline in summary should be stripped, interior newlines preserved
        assertEquals("First line\n\nDetailed description.\n\nFixes #123".stripTrailing(), result);
    }
}
