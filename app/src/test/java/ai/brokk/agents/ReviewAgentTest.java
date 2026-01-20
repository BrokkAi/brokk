package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.DiffService;
import ai.brokk.git.GitRepoData.FileDiff;
import ai.brokk.testutil.InlineTestProjectCreator;
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
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void testFindFileDiff() {
        ProjectFile oldFile = new ProjectFile(tempDir, "old_path.java");
        ProjectFile newFile = new ProjectFile(tempDir, "new_path.java");
        var diff = new FileDiff(oldFile, newFile, "old", "new");
        var diffs = List.of(diff);

        assertEquals(diff, ReviewAgent.findFileDiff("old_path.java", diffs));
        assertEquals(diff, ReviewAgent.findFileDiff("new_path.java", diffs));
        assertNull(ReviewAgent.findFileDiff("other.java", diffs));
    }

    @Test
    void testComputeCumulativeDiff_betweenCommits() throws IOException, GitAPIException {
        // Create project with initial commit
        var project = InlineTestProjectCreator.code("line1\n", "file.txt")
                .withGit()
                .addCommit("file.txt", "file.txt")
                .build();

        var repo = (ai.brokk.git.GitRepo) project.getRepo();
        String initialCommit = repo.getCurrentCommitId();

        // Make a change and commit it
        ProjectFile pf = new ProjectFile(project.getRoot(), "file.txt");
        Files.writeString(pf.absPath(), "line1\nline2\n");
        repo.add(pf);
        repo.commitCommand().setMessage("Add line2").call();
        String secondCommit = repo.getCurrentCommitId();

        // Test computeCumulativeDiff directly between two commits
        var commits = repo.listCommitsBetweenBranches(initialCommit, secondCommit, false);
        var cumulativeChanges = DiffService.computeCumulativeDiff(repo, initialCommit, secondCommit, commits);

        assertNotNull(cumulativeChanges);
        assertEquals(1, cumulativeChanges.filesChanged());
        assertEquals(1, cumulativeChanges.totalAdded());
        assertFalse(cumulativeChanges.commits().isEmpty());

        project.close();
    }

    @Test
    void testComputeCumulativeDiff_noChanges() throws IOException, GitAPIException {
        // Create project with initial commit
        var project = InlineTestProjectCreator.code("line1\n", "file.txt")
                .withGit()
                .addCommit("file.txt", "file.txt")
                .build();

        var repo = (ai.brokk.git.GitRepo) project.getRepo();
        String currentCommit = repo.getCurrentCommitId();

        // Diff HEAD to HEAD should show no changes
        var cumulativeChanges = DiffService.computeCumulativeDiff(repo, currentCommit, currentCommit, List.of());

        assertNotNull(cumulativeChanges);
        assertEquals(0, cumulativeChanges.filesChanged());
        assertTrue(cumulativeChanges.commits().isEmpty());

        project.close();
    }

    @Test
    void testFromBaseline_simpleRef() throws IOException {
        var project = InlineTestProjectCreator.code("line1\n", "file.txt")
                .withGit()
                .addCommit("file.txt", "file.txt")
                .build();
        IContextManager cm = new TestContextManager(project);

        // Smoke test: should handle "HEAD" ref without crashing
        var ctx = ReviewScope.fromBaseline(cm, "HEAD");
        assertNotNull(ctx);
        assertTrue(ctx.changes().perFileChanges().isEmpty());
        project.close();
    }

    @Test
    void testFindOverlappingSessions_returnsEmptyForNoCommits() {
        // Test the edge case where commits list is empty
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);

        var overlapping = ReviewScope.findOverlappingSessions(cm, List.of());

        assertTrue(overlapping.isEmpty(), "Empty commits list should return empty overlapping sessions");
    }

    @Test
    void testRetryInStages_accumulatesGoodExcerptsAcrossRetries()
            throws InterruptedException, IOException, ReviewGenerationException {
        Files.writeString(tempDir.resolve("file1.java"), "content1");
        Files.writeString(tempDir.resolve("file2.java"), "content2");
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);

        ProjectFile f1 = new ProjectFile(tempDir, "file1.java");
        ProjectFile f2 = new ProjectFile(tempDir, "file2.java");
        var d1 = new FileDiff(f1, f1, "content1", "content1");
        var d2 = new FileDiff(f2, f2, "content2", "content2");

        var changes = new DiffService.CumulativeChanges(2, 0, 0, List.of(d1, d2), List.of());
        ReviewAgent agent = new ReviewAgent(changes, List.of(), cm);

        // Turn 1: Excerpt for file1 is good (index 0), Excerpt for wrong.java is bad (index 1)
        String resp1 =
                """
            At `file1.java` line 1:
            ```java
            content1
            ```

            At `wrong.java` line 1:
            ```java
            content2
            ```
            """;

        // Retry 1: Provides the fixed Excerpt for index 1 in the expected "Excerpt 1:" format.
        // The implementation must remember file1 (index 0) from the previous turn.
        String resp2 =
                """
            Excerpt 1:
            At `file2.java` line 1:
            ```java
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
        var changes = new DiffService.CumulativeChanges(0, 0, 0, List.of(), List.of());
        ReviewAgent agent = new ReviewAgent(changes, List.of(), cm);

        // Always return the same bad excerpt
        String badResp =
                """
            At `missing.java` line 1:
            ```java
            content
            ```
            """;

        // Retry responses in expected format but still bad (referencing the same missing file).
        // MAX_ATTEMPTS is 3.
        String badRetry =
                """
            Excerpt 0:
            At `missing.java` line 1:
            ```java
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

        ProjectFile f1 = new ProjectFile(tempDir, "file1.java");
        var d1 = new FileDiff(f1, f1, "content1", "content1");

        var changes = new DiffService.CumulativeChanges(1, 0, 0, List.of(d1), List.of());
        ReviewAgent agent = new ReviewAgent(changes, List.of(), cm);

        // Path is bad initially
        String resp1 = "At `bad.java` line 1:\n```\nc1\n```";
        // Stage 1: Fix path on first attempt. Total retries = 1.
        String resp2 = "Excerpt 0:\nAt `file1.java` line 1:\n```\nc1\n```";
        // Stage 2: Content "c1" doesn't match "content1".
        // Let's provide 4 bad content fixes. It should stop after 3.
        String badContentFix = "Excerpt 0:\nAt `file1.java` line 1:\n```\nstill-wrong\n```";

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
    void testRetryInStages_integrationScenarios() throws InterruptedException, IOException, ReviewGenerationException {
        Files.writeString(tempDir.resolve("file.java"), "line1\nline2\nline3\nline4");
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);

        ProjectFile f = new ProjectFile(tempDir, "file.java");
        var diff = new FileDiff(f, f, "line1\nline2\nline3\nline4", "line1\nline2-new\nline3\nline4");
        var changes = new DiffService.CumulativeChanges(1, 1, 1, List.of(diff), List.of());
        ReviewAgent agent = new ReviewAgent(changes, List.of(), cm);

        // 1. Content normalization (excerpt has \r\n, file has \n - WhitespaceMatch handles this)
        String resp1 =
                """
            At `file.java` line 1:
            ```java
            line1\r\nline2-new
            ```

            At `file.java` line 3:
            ```java
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
            At `file.java` line 1:
            ```java
            no-match-here
            ```
            """;
        // Fix response in expected "Excerpt N:" format
        String fixContent =
                """
            Excerpt 0:
            At `file.java` line 4:
            ```java
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

        ProjectFile f1 = new ProjectFile(tempDir, "good.java");
        ProjectFile f2 = new ProjectFile(tempDir, "fixed.java");
        var d1 = new FileDiff(f1, f1, "public class Good {}", "public class Good {}");
        var d2 = new FileDiff(f2, f2, "public class Fixed {}", "public class Fixed {}");

        var changes = new DiffService.CumulativeChanges(2, 0, 0, List.of(d1, d2), List.of());
        ReviewAgent agent = new ReviewAgent(changes, List.of(), cm);

        // Initial response: good.java is good, bad.java has bad path
        String resp1 =
                """
            At `good.java` line 1:
            ```java
            public class Good {}
            ```

            At `bad.java` line 1:
            ```java
            public class Fixed {}
            ```
            """;

        // Retry response: Fixes bad path to fixed.java in expected "Excerpt N:" format
        String resp2 =
                """
            Excerpt 1:
            At `fixed.java` line 1:
            ```java
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

        ProjectFile f1 = new ProjectFile(tempDir, "file1.java");
        ProjectFile f2 = new ProjectFile(tempDir, "file2.java");
        var d1 = new FileDiff(f1, f1, "content1", "content1");
        var d2 = new FileDiff(f2, f2, "content2", "content2");

        var changes = new DiffService.CumulativeChanges(2, 0, 0, List.of(d1, d2), List.of());
        ReviewAgent agent = new ReviewAgent(changes, List.of(), cm);

        // Scenario: Text, Excerpt for file1 (Good), Text, Excerpt for missing.java (Bad Path), Text
        String resp1 =
                """
                Before 0.
                At `file1.java` line 1:
                ```java
                content1
                ```
                Between 0 and 1.
                At `missing.java` line 1:
                ```java
                content2
                ```
                After 1.""";

        // Fix for the bad path excerpt in expected "Excerpt N:" format
        String resp2 =
                """
                Excerpt 1:
                At `file2.java` line 1:
                ```java
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

        ProjectFile f = new ProjectFile(tempDir, "file.java");
        var diff = new FileDiff(f, f, "line1\nline2", "line1\nline2");
        var changes = new DiffService.CumulativeChanges(1, 0, 0, List.of(diff), List.of());
        ReviewAgent agent = new ReviewAgent(changes, List.of(), cm);

        // Valid response with proper recommendation
        String resp1 = "## Overview\n" + "Some overview.\n"
                + "\n"
                + "## Tactical Notes\n"
                + "### Good Note\n"
                + "At `file.java` line 1:\n"
                + "```java\n"
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

        ProjectFile f = new ProjectFile(tempDir, "file.java");
        var diff = new FileDiff(f, f, "line1\nline2\nline3", "line1\nline2\nline3");
        var changes = new DiffService.CumulativeChanges(1, 0, 0, List.of(diff), List.of());
        ReviewAgent agent = new ReviewAgent(changes, List.of(), cm);

        // Turn 1: badpath.java doesn't exist
        String resp1 =
                """
                Intro.
                At `badpath.java` line 1:
                ```java
                line1
                ```
                Outro.""";

        // Turn 2: Fix bad path to file.java in expected "Excerpt N:" format
        String resp2 =
                """
                Excerpt 0:
                At `file.java` line 1:
                ```java
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

    @Test
    void testRetryInStages_excerptsStructureIsStable_rejectsUnrequestedIndexChangeInStage1()
            throws InterruptedException, IOException, ReviewGenerationException {
        Files.writeString(tempDir.resolve("file1.java"), "content1");
        Files.writeString(tempDir.resolve("file2.java"), "content2");
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);

        ProjectFile f1 = new ProjectFile(tempDir, "file1.java");
        ProjectFile f2 = new ProjectFile(tempDir, "file2.java");
        var d1 = new FileDiff(f1, f1, "content1", "content1");
        var d2 = new FileDiff(f2, f2, "content2", "content2");

        var changes = new DiffService.CumulativeChanges(2, 0, 0, List.of(d1, d2), List.of());
        ReviewAgent agent = new ReviewAgent(changes, List.of(), cm);

        // Turn 1: excerpt 0 good, excerpt 1 has bad path -> triggers Stage 1 retry for index 1 only.
        String resp1 =
                """
                At `file1.java` line 1:
                ```java
                content1
                ```

                At `missing.java` line 1:
                ```java
                content2
                ```
                """;

        // Turn 2: LLM fixes excerpt 1, but also (incorrectly) provides an "Excerpt 0" correction
        // that changes index 0 even though it was not requested. This should be rejected and discarded.
        String resp2 =
                """
                Excerpt 1:
                At `file2.java` line 1:
                ```java
                content2
                ```

                Excerpt 0:
                At `file2.java` line 1:
                ```java
                content2
                ```
                """;

        // Turn 3: correct fix with no unrequested changes.
        String resp3 =
                """
                Excerpt 1:
                At `file2.java` line 1:
                ```java
                content2
                ```
                """;

        var stubModel = new TestScriptedLanguageModel(resp1, resp2, resp3);
        var llm = new Llm(stubModel, "test", cm, false, false, false, false);
        var turn1Result = llm.sendRequest(List.of(new UserMessage("start")));

        ReviewAgent.RetryResult retryResult = agent.retryInStages(llm, new ArrayList<>(), turn1Result);

        assertEquals(2, retryResult.retryCount(), "Discarded turn should still count toward retry budget");
        assertEquals(2, retryResult.resolvedExcerpts().size(), "Should still resolve both excerpts after a good turn");

        assertEquals("file1.java", retryResult.resolvedExcerpts().get(0).file().toString());
        assertEquals("file2.java", retryResult.resolvedExcerpts().get(1).file().toString());
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
