package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.difftool.ui.FileComparisonInfo;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.ReviewParser;
import ai.brokk.util.ReviewParser.CodeExcerpt;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void testFindFileComparison() {
        var left = new BufferSource.StringSource("old", "OLD", "old_path.java");
        var right = new BufferSource.StringSource("new", "NEW", "new_path.java");
        var info = new FileComparisonInfo(null, left, right);
        var comparisons = List.of(info);

        assertEquals(info, ReviewAgent.findFileComparison("old_path.java", comparisons));
        assertEquals(info, ReviewAgent.findFileComparison("new_path.java", comparisons));
        assertNull(ReviewAgent.findFileComparison("other.java", comparisons));
    }

    @Test
    void testMatchExcerptInFile() {
        var left = new BufferSource.StringSource("line1\nline2\nline3", "OLD", "test.java");
        var right = new BufferSource.StringSource("line1\nline2-new\nline3", "NEW", "test.java");
        var info = new FileComparisonInfo(null, left, right);

        // Match in NEW
        var excerptNew = new ReviewParser.RawExcerpt("test.java", 2, "line2-new");
        var matchNew = ReviewAgent.matchExcerptInFile(excerptNew, info);
        assertNotNull(matchNew);
        assertEquals(2, matchNew.line());
        assertEquals(ReviewParser.DiffSide.NEW, matchNew.side());

        // Match in OLD (not in new)
        var excerptOld = new ReviewParser.RawExcerpt("test.java", 2, "line2");
        var matchOld = ReviewAgent.matchExcerptInFile(excerptOld, info);
        assertNotNull(matchOld);
        assertEquals(2, matchOld.line());
        assertEquals(ReviewParser.DiffSide.OLD, matchOld.side());

        // Whitespace insensitive
        var excerptWS = new ReviewParser.RawExcerpt("test.java", 1, "  line1  ");
        var matchWS = ReviewAgent.matchExcerptInFile(excerptWS, info);
        assertNotNull(matchWS);
        assertEquals(1, matchWS.line());

        // No match
        var excerptNone = new ReviewParser.RawExcerpt("test.java", 1, "garbage");
        assertNull(ReviewAgent.matchExcerptInFile(excerptNone, info));

        // Multi-line match
        var multiLeft = new BufferSource.StringSource("a\nb\nc\nd\ne", "OLD", "multi.java");
        var multiInfo = new FileComparisonInfo(null, multiLeft, multiLeft);
        var multiExcerpt = new ReviewParser.RawExcerpt("multi.java", 3, "b\nc\nd");
        var multiMatch = ReviewAgent.matchExcerptInFile(multiExcerpt, multiInfo);
        assertNotNull(multiMatch);
        assertEquals(2, multiMatch.line()); // Starts at line 2
        assertEquals("b\nc\nd", multiMatch.matchedText());
    }

    @Test
    void testRetryInStages_accumulatesGoodExcerptsAcrossRetries()
            throws InterruptedException, IOException, ReviewGenerationException {
        Files.writeString(tempDir.resolve("file1.java"), "content1");
        Files.writeString(tempDir.resolve("file2.java"), "content2");
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);

        var info1 = new FileComparisonInfo(
                null,
                new BufferSource.StringSource("content1", "NEW", "file1.java"),
                new BufferSource.StringSource("content1", "NEW", "file1.java"));
        var info2 = new FileComparisonInfo(
                null,
                new BufferSource.StringSource("content2", "NEW", "file2.java"),
                new BufferSource.StringSource("content2", "NEW", "file2.java"));

        ReviewAgent agent = new ReviewAgent(cm, cm.getIo(), List.of(info1, info2));

        // Turn 1: Excerpt for file1 is good (index 0), Excerpt for wrong.java is bad (index 1)
        String resp1 =
                """
            ```java
            file1.java @1
            content1
            ```

            ```java
            wrong.java @1
            content2
            ```
            """;

        // Retry 1: Provides the fixed Excerpt for index 1 in the expected "Excerpt 1:" format.
        // The implementation must remember file1 (index 0) from the previous turn.
        String resp2 =
                """
            Excerpt 1:
            ```java
            file2.java @1
            content2
            ```
            """;

        var stubModel = new TestScriptedLanguageModel(resp1, resp2);
        var llm = new Llm(stubModel, "test", cm, false, false, false, false);

        var initialMessages = new ArrayList<ChatMessage>();
        initialMessages.add(new UserMessage("analyze"));
        var turn1Result = llm.sendRequest(initialMessages);

        Map<Integer, CodeExcerpt> result =
                agent.retryInStages(llm, new ArrayList<>(), turn1Result).resolvedExcerpts();

        assertEquals(2, result.size());
        assertEquals("file1.java", result.get(0).file().toString());
        assertEquals("file2.java", result.get(1).file().toString());
    }

    @Test
    void testRetryInStages_exhaustsRetries() throws InterruptedException, ReviewGenerationException {
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);
        ReviewAgent agent = new ReviewAgent(cm, cm.getIo(), List.of());

        // Always return the same bad excerpt
        String badResp =
                """
            ```java
            missing.java @1
            content
            ```
            """;

        // Retry responses in expected format but still bad (referencing the same missing file).
        // MAX_ATTEMPTS is 3.
        String badRetry =
                """
            Excerpt 0:
            ```java
            missing.java @1
            content
            ```
            """;

        var stubModel = new TestScriptedLanguageModel(badResp, badRetry, badRetry, badRetry, badRetry);
        var llm = new Llm(stubModel, "test", cm, false, false, false, false);

        var initialMessages = new ArrayList<ChatMessage>();
        initialMessages.add(new UserMessage("analyze"));
        var turn1Result = llm.sendRequest(initialMessages);

        var retryResult = agent.retryInStages(llm, new ArrayList<>(), turn1Result);

        // Should be empty because no valid files were ever found across all retries
        assertTrue(retryResult.resolvedExcerpts().isEmpty());
        // 3 retries in Stage 1 + 0 retries in Stage 2 (since validPathExcerpts is empty)
        assertEquals(3, retryResult.retryCount());
    }

    @Test
    void testRetryInStages_limitsTotalAttempts() throws InterruptedException, IOException, ReviewGenerationException {
        Files.writeString(tempDir.resolve("file1.java"), "content1");
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);

        var info1 = new FileComparisonInfo(
                null,
                new BufferSource.StringSource("content1", "NEW", "file1.java"),
                new BufferSource.StringSource("content1", "NEW", "file1.java"));

        ReviewAgent agent = new ReviewAgent(cm, cm.getIo(), List.of(info1));

        // Path is bad initially
        String resp1 = "```\nbad.java @1\nc1\n```";
        // Stage 1: Fix path on first attempt. Total retries = 1.
        String resp2 = "Excerpt 0:\n```\nfile1.java @1\nc1\n```";
        // Stage 2: Content "c1" doesn't match "content1".
        // Let's provide 4 bad content fixes. It should stop after 3.
        String badContentFix = "Excerpt 0:\n```\nfile1.java @1\nstill-wrong\n```";

        var stubModel =
                new TestScriptedLanguageModel(resp1, resp2, badContentFix, badContentFix, badContentFix, badContentFix);
        var llm = new Llm(stubModel, "test", cm, false, false, false, false);
        var turn1Result = llm.sendRequest(List.of(new UserMessage("start")));

        var result = agent.retryInStages(llm, new ArrayList<>(), turn1Result);
        // Stage 1 took 1 retry. Stage 2 took 3 retries (limit). Total = 4.
        assertEquals(4, result.retryCount());
        assertTrue(result.resolvedExcerpts().isEmpty());
    }

    @Test
    void testMatchExcerptInFile_emptyAndFull() {
        var emptySource = new BufferSource.StringSource("", "NEW", "empty.java");
        var info = new FileComparisonInfo(null, emptySource, emptySource);
        var excerpt = new ReviewParser.RawExcerpt("empty.java", 1, "content");

        assertNull(ReviewAgent.matchExcerptInFile(excerpt, info));

        // Excerpt spans entire file
        var content = "line1\nline2";
        var source = new BufferSource.StringSource(content, "NEW", "full.java");
        var fullInfo = new FileComparisonInfo(null, source, source);
        var fullExcerpt = new ReviewParser.RawExcerpt("full.java", 1, content);

        var match = ReviewAgent.matchExcerptInFile(fullExcerpt, fullInfo);
        assertNotNull(match);
        assertEquals(1, match.line());
    }

    @Test
    void testRetryInStages_integrationScenarios() throws InterruptedException, IOException, ReviewGenerationException {
        Files.writeString(tempDir.resolve("file.java"), "line1\nline2\nline3\nline4");
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);

        var info = new FileComparisonInfo(
                null,
                new BufferSource.StringSource("line1\nline2\nline3\nline4", "OLD", "file.java"),
                new BufferSource.StringSource("line1\nline2-new\nline3\nline4", "NEW", "file.java"));
        ReviewAgent agent = new ReviewAgent(cm, cm.getIo(), List.of(info));

        // 1. Content normalization (excerpt has \r\n, file has \n - WhitespaceMatch handles this)
        String resp1 =
                """
            ```java
            file.java @1
            line1\r\nline2-new
            ```

            ```java
            file.java @3
            line3
            ```
            """;

        var stubModel = new TestScriptedLanguageModel(resp1);
        var llm = new Llm(stubModel, "test", cm, false, false, false, false);
        var initialMessages = new ArrayList<ChatMessage>();
        initialMessages.add(new UserMessage("analyze"));
        var turn1Result = llm.sendRequest(initialMessages);

        Map<Integer, CodeExcerpt> result =
                agent.retryInStages(llm, new ArrayList<>(), turn1Result).resolvedExcerpts();

        assertEquals(2, result.size());

        // 3. Stage 1 passes (file exists), but Stage 2 fails (content doesn't match)
        String mismatchContent =
                """
            ```java
            file.java @1
            no-match-here
            ```
            """;
        // Fix response in expected "Excerpt N:" format
        String fixContent =
                """
            Excerpt 0:
            ```java
            file.java @4
            line4
            ```
            """;

        var stage2Model = new TestScriptedLanguageModel(mismatchContent, fixContent);
        var stage2Llm = new Llm(stage2Model, "test", cm, false, false, false, false);
        var t1Result = stage2Llm.sendRequest(initialMessages);

        Map<Integer, CodeExcerpt> result2 =
                agent.retryInStages(stage2Llm, new ArrayList<>(), t1Result).resolvedExcerpts();
        assertEquals(1, result2.size());
        assertEquals(4, result2.get(0).line());
    }

    @Test
    void testRetryInStages_Stage1AccumulatesExcerpts()
            throws InterruptedException, IOException, ReviewGenerationException {
        Files.writeString(tempDir.resolve("good.java"), "public class Good {}");
        Files.writeString(tempDir.resolve("fixed.java"), "public class Fixed {}");
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);

        var info1 = new FileComparisonInfo(
                null,
                new BufferSource.StringSource("public class Good {}", "NEW", "good.java"),
                new BufferSource.StringSource("public class Good {}", "NEW", "good.java"));
        var info2 = new FileComparisonInfo(
                null,
                new BufferSource.StringSource("public class Fixed {}", "NEW", "fixed.java"),
                new BufferSource.StringSource("public class Fixed {}", "NEW", "fixed.java"));

        ReviewAgent agent = new ReviewAgent(cm, cm.getIo(), List.of(info1, info2));

        // Initial response: good.java is good, bad.java has bad path
        String resp1 =
                """
            ```java
            good.java @1
            public class Good {}
            ```

            ```java
            bad.java @1
            public class Fixed {}
            ```
            """;

        // Retry response: Fixes bad path to fixed.java in expected "Excerpt N:" format
        String resp2 =
                """
            Excerpt 1:
            ```java
            fixed.java @1
            public class Fixed {}
            ```
            """;

        var stubModel = new TestScriptedLanguageModel(resp1, resp2);
        var llm = new Llm(stubModel, "test", cm, false, false, false, false);
        var turn1Result = llm.sendRequest(List.of(new UserMessage("start")));

        Map<Integer, CodeExcerpt> result =
                agent.retryInStages(llm, new ArrayList<>(), turn1Result).resolvedExcerpts();

        assertEquals(2, result.size(), "Should have accumulated both excerpts");
        assertEquals("good.java", result.get(0).file().toString());
        assertEquals("fixed.java", result.get(1).file().toString());
    }

    @Test
    void testRetryInStages_PreservesInterleavedStructure()
            throws InterruptedException, IOException, ReviewGenerationException {
        Files.writeString(tempDir.resolve("file1.java"), "content1");
        Files.writeString(tempDir.resolve("file2.java"), "content2");
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);

        var info1 = new FileComparisonInfo(
                null,
                new BufferSource.StringSource("content1", "NEW", "file1.java"),
                new BufferSource.StringSource("content1", "NEW", "file1.java"));
        var info2 = new FileComparisonInfo(
                null,
                new BufferSource.StringSource("content2", "NEW", "file2.java"),
                new BufferSource.StringSource("content2", "NEW", "file2.java"));

        ReviewAgent agent = new ReviewAgent(cm, cm.getIo(), List.of(info1, info2));

        // Scenario: Text, Excerpt for file1 (Good), Text, Excerpt for missing.java (Bad Path), Text
        String resp1 =
                """
                Before 0.
                ```java
                file1.java @1
                content1
                ```
                Between 0 and 1.
                ```java
                missing.java @1
                content2
                ```
                After 1.""";

        // Fix for the bad path excerpt in expected "Excerpt N:" format
        String resp2 =
                """
                Excerpt 1:
                ```java
                file2.java @1
                content2
                ```""";

        var stubModel = new TestScriptedLanguageModel(resp1, resp2);
        var llm = new Llm(stubModel, "test", cm, false, false, false, false);
        var turn1Result = llm.sendRequest(List.of(new UserMessage("start")));

        ReviewAgent.RetryResult retryResult = agent.retryInStages(llm, new ArrayList<>(), turn1Result);
        Map<Integer, CodeExcerpt> resolved = retryResult.resolvedExcerpts();

        assertEquals(2, resolved.size());

        // Verify structure preservation (mirroring the logic in ReviewAgent.execute for mergedReviewText)
        List<ReviewParser.Segment> segments = ReviewParser.instance.parseToSegments(resp1);
        assertEquals(5, segments.size());
        assertTrue(segments.get(0) instanceof ReviewParser.TextSegment);
        assertTrue(((ReviewParser.TextSegment) segments.get(0)).text().contains("Before 0."));
        assertTrue(segments.get(2) instanceof ReviewParser.TextSegment);
        assertTrue(((ReviewParser.TextSegment) segments.get(2)).text().contains("Between 0 and 1."));
        assertTrue(segments.get(4) instanceof ReviewParser.TextSegment);
        assertTrue(((ReviewParser.TextSegment) segments.get(4)).text().contains("After 1."));
    }

    @Test
    void testRetryInStages_noValidationErrorsSkipsCorrection()
            throws InterruptedException, IOException, ReviewGenerationException {
        // Test that when there are no validation errors, we go straight to excerpt resolution
        Files.writeString(tempDir.resolve("file.java"), "line1\nline2");
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);

        var info = new FileComparisonInfo(
                null,
                new BufferSource.StringSource("line1\nline2", "NEW", "file.java"),
                new BufferSource.StringSource("line1\nline2", "NEW", "file.java"));
        ReviewAgent agent = new ReviewAgent(cm, cm.getIo(), List.of(info));

        // Valid response with proper recommendation
        String resp1 = "## Overview\n" + "Some overview.\n"
                + "\n"
                + "## Tactical Notes\n"
                + "### Good Note\n"
                + "```java\n"
                + "file.java @1\n"
                + "line1\n"
                + "```\n"
                + "Description here.\n"
                + "**Recommendation:** Do the thing.\n";

        var stubModel = new TestScriptedLanguageModel(resp1);
        var llm = new Llm(stubModel, "test", cm, false, false, false, false);
        var turn1Result = llm.sendRequest(List.of(new UserMessage("start")));

        ReviewAgent.RetryResult result = agent.retryInStages(llm, new ArrayList<>(), turn1Result);

        assertEquals(1, result.resolvedExcerpts().size());
        assertEquals(0, result.retryCount(), "No retries needed when everything is valid");
    }

    @Test
    void testRetryInStages_twoStageResolution() throws InterruptedException, IOException, ReviewGenerationException {
        // Test that Stage 1 (file path) and Stage 2 (content matching) work in sequence
        Files.writeString(tempDir.resolve("file.java"), "line1\nline2\nline3");
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);

        var info = new FileComparisonInfo(
                null,
                new BufferSource.StringSource("line1\nline2\nline3", "NEW", "file.java"),
                new BufferSource.StringSource("line1\nline2\nline3", "NEW", "file.java"));
        ReviewAgent agent = new ReviewAgent(cm, cm.getIo(), List.of(info));

        // Turn 1: badpath.java doesn't exist
        String resp1 =
                """
                Intro.
                ```java
                badpath.java @1
                line1
                ```
                Outro.""";

        // Turn 2: Fix bad path to file.java in expected "Excerpt N:" format
        String resp2 =
                """
                Excerpt 0:
                ```java
                file.java @1
                line1
                ```""";

        var stubModel = new TestScriptedLanguageModel(resp1, resp2);
        var llm = new Llm(stubModel, "test", cm, false, false, false, false);
        var turn1Result = llm.sendRequest(List.of(new UserMessage("start")));

        ReviewAgent.RetryResult retryResult = agent.retryInStages(llm, new ArrayList<>(), turn1Result);
        Map<Integer, CodeExcerpt> resolved = retryResult.resolvedExcerpts();

        assertEquals(1, resolved.size());
        assertEquals(1, resolved.get(0).line());
        assertEquals("line1", resolved.get(0).excerpt());
        assertEquals(1, retryResult.retryCount());
    }

    private static class TestScriptedLanguageModel implements StreamingChatModel {
        private final List<String> responses;
        private final AtomicInteger index = new AtomicInteger(0);

        TestScriptedLanguageModel(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            int i = index.getAndIncrement();
            String content = i < responses.size() ? responses.get(i) : responses.getLast();
            AiMessage aiMessage = AiMessage.from(content);
            handler.onPartialResponse(content);
            handler.onCompleteResponse(
                    ChatResponse.builder().aiMessage(aiMessage).build());
        }
    }
}
