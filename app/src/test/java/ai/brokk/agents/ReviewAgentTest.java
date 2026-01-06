package ai.brokk.agents;

import ai.brokk.ICodeReview;
import ai.brokk.ICodeReview.CodeExcerpt;
import ai.brokk.IContextManager;
import ai.brokk.difftool.ui.BufferSource;
import ai.brokk.difftool.ui.FileComparisonInfo;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.WhitespaceMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import ai.brokk.Llm;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

class ReviewAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void testValidateFiles() throws IOException {
        Files.writeString(tempDir.resolve("exists.java"), "public class Exists {}");
        
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);

        Map<Integer, CodeExcerpt> excerpts = Map.of(
            0, new CodeExcerpt("exists.java", 0, ICodeReview.DiffSide.NEW, "code"),
            1, new CodeExcerpt("missing.java", 0, ICodeReview.DiffSide.NEW, "code")
        );

        Map<Integer, String> errors = ReviewAgent.validateFiles(excerpts, cm);

        assertEquals(1, errors.size());
        assertTrue(errors.containsKey(1));
        assertTrue(errors.get(1).contains("File does not exist"));
    }

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
    void testFindBestMatch() {
        var matches = List.of(
            new WhitespaceMatch(10, "line 11"),
            new WhitespaceMatch(20, "line 21"),
            new WhitespaceMatch(30, "line 31")
        );

        // Exact match
        assertEquals(10, ReviewAgent.findBestMatch(matches, 11).startLine());

        // Target line before all matches
        assertEquals(10, ReviewAgent.findBestMatch(matches, 5).startLine());

        // Target line after all matches
        assertEquals(30, ReviewAgent.findBestMatch(matches, 50).startLine());
        
        // Closer to middle
        assertEquals(20, ReviewAgent.findBestMatch(matches, 22).startLine());
        
        // Closer to end
        assertEquals(30, ReviewAgent.findBestMatch(matches, 28).startLine());

        // Tie-breaker (first one wins)
        assertEquals(10, ReviewAgent.findBestMatch(matches, 16).startLine());
    }

    @Test
    void testMatchExcerptInFile() {
        var left = new BufferSource.StringSource("line1\nline2\nline3", "OLD", "test.java");
        var right = new BufferSource.StringSource("line1\nline2-new\nline3", "NEW", "test.java");
        var info = new FileComparisonInfo(null, left, right);

        // Match in NEW
        var excerptNew = new CodeExcerpt("test.java", 2, ICodeReview.DiffSide.NEW, "line2-new");
        var matchNew = ReviewAgent.matchExcerptInFile(excerptNew, info);
        assertNotNull(matchNew);
        assertEquals(2, matchNew.line());
        assertEquals(ICodeReview.DiffSide.NEW, matchNew.side());

        // Match in OLD (not in new)
        var excerptOld = new CodeExcerpt("test.java", 2, ICodeReview.DiffSide.NEW, "line2");
        var matchOld = ReviewAgent.matchExcerptInFile(excerptOld, info);
        assertNotNull(matchOld);
        assertEquals(2, matchOld.line());
        assertEquals(ICodeReview.DiffSide.OLD, matchOld.side());

        // Whitespace insensitive
        var excerptWS = new CodeExcerpt("test.java", 1, ICodeReview.DiffSide.NEW, "  line1  ");
        var matchWS = ReviewAgent.matchExcerptInFile(excerptWS, info);
        assertNotNull(matchWS);
        assertEquals(1, matchWS.line());

        // No match
        var excerptNone = new CodeExcerpt("test.java", 1, ICodeReview.DiffSide.NEW, "garbage");
        assertNull(ReviewAgent.matchExcerptInFile(excerptNone, info));

        // Multi-line match
        var multiLeft = new BufferSource.StringSource("a\nb\nc\nd\ne", "OLD", "multi.java");
        var multiInfo = new FileComparisonInfo(null, multiLeft, multiLeft);
        var multiExcerpt = new CodeExcerpt("multi.java", 3, ICodeReview.DiffSide.NEW, "b\nc\nd");
        var multiMatch = ReviewAgent.matchExcerptInFile(multiExcerpt, multiInfo);
        assertNotNull(multiMatch);
        assertEquals(2, multiMatch.line()); // Starts at line 2
        assertEquals("b\nc\nd", multiMatch.matchedText());
    }

    @Test
    void testRetryInStages_accumulatesGoodExcerpts() throws InterruptedException, IOException {
        Files.writeString(tempDir.resolve("file1.java"), "content1");
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);
        
        var info = new FileComparisonInfo(
            null, 
            new BufferSource.StringSource("content1", "OLD", "file1.java"),
            new BufferSource.StringSource("content1", "NEW", "file1.java")
        );
        
        ReviewAgent agent = new ReviewAgent("diff", cm, null, List.of(info));

        // Response 1: Excerpt 0 is good, Excerpt 1 is bad (wrong file)
        String resp1 = """
            BRK_EXCERPT_0
            file1.java @1
            ```java
            content1
            ```
            
            BRK_EXCERPT_1
            nonexistent.java @1
            ```java
            missing
            ```
            """;
            
        // Response 2: Fixes Excerpt 1
        String resp2 = """
            BRK_EXCERPT_1
            file1.java @1
            ```java
            content1
            ```
            """;

        var stubModel = new TestScriptedLanguageModel(resp1, resp2);
        var llm = new Llm(stubModel, "test", cm, false, false, false, false);

        // Llm.sendRequest requires at least one message
        var initialMessages = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
        initialMessages.add(new dev.langchain4j.data.message.UserMessage("analyze"));
        var turn1Result = llm.sendRequest(initialMessages);

        Map<Integer, CodeExcerpt> result = agent.retryInStages(llm, new ArrayList<>(), turn1Result);

        assertEquals(2, result.size(), "Should have accumulated both excerpts");
        assertTrue(result.containsKey(0));
        assertTrue(result.containsKey(1));
    }

    @Test
    void testRetryInStages_exhaustsRetries() throws InterruptedException {
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);
        ReviewAgent agent = new ReviewAgent("diff", cm, null, List.of());

        // Always return the same bad excerpt
        String badResp = """
            BRK_EXCERPT_0
            missing.java @1
            ```java
            content
            ```
            """;

        var stubModel = new TestScriptedLanguageModel(badResp, badResp, badResp, badResp, badResp);
        var llm = new Llm(stubModel, "test", cm, false, false, false, false);

        var initialMessages = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
        initialMessages.add(new dev.langchain4j.data.message.UserMessage("analyze"));
        var turn1Result = llm.sendRequest(initialMessages);

        Map<Integer, CodeExcerpt> result = agent.retryInStages(llm, new ArrayList<>(), turn1Result);

        // Should be empty because no valid files were ever found across all retries
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindBestMatch_additionalEdgeCases() {
        var matches = List.of(
            new WhitespaceMatch(10, "line 11"),
            new WhitespaceMatch(20, "line 21")
        );

        // Single match
        var single = List.of(new WhitespaceMatch(5, "only"));
        assertEquals(5, ReviewAgent.findBestMatch(single, 100).startLine());

        // Target line before all matches
        assertEquals(10, ReviewAgent.findBestMatch(matches, 1).startLine());

        // Target line after all matches
        assertEquals(20, ReviewAgent.findBestMatch(matches, 30).startLine());
    }

    @Test
    void testMatchExcerptInFile_emptyAndFull() {
        var emptySource = new BufferSource.StringSource("", "NEW", "empty.java");
        var info = new FileComparisonInfo(null, emptySource, emptySource);
        var excerpt = new CodeExcerpt("empty.java", 1, ICodeReview.DiffSide.NEW, "content");
        
        assertNull(ReviewAgent.matchExcerptInFile(excerpt, info));

        // Excerpt spans entire file
        var content = "line1\nline2";
        var source = new BufferSource.StringSource(content, "NEW", "full.java");
        var fullInfo = new FileComparisonInfo(null, source, source);
        var fullExcerpt = new CodeExcerpt("full.java", 1, ICodeReview.DiffSide.NEW, content);
        
        var match = ReviewAgent.matchExcerptInFile(fullExcerpt, fullInfo);
        assertNotNull(match);
        assertEquals(1, match.line());
    }

    @Test
    void testRetryInStages_integrationScenarios() throws InterruptedException, IOException {
        Files.writeString(tempDir.resolve("file.java"), "line1\nline2\nline3\nline4");
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);
        
        var info = new FileComparisonInfo(
            null, 
            new BufferSource.StringSource("line1\nline2\nline3\nline4", "OLD", "file.java"),
            new BufferSource.StringSource("line1\nline2-new\nline3\nline4", "NEW", "file.java")
        );
        ReviewAgent agent = new ReviewAgent("diff", cm, null, List.of(info));

        // 1. Excerpt ID gaps (0 and 5)
        // 2. Content normalization (excerpt has \r\n, file has \n - WhitespaceMatch handles this)
        String resp1 = """
            BRK_EXCERPT_0
            file.java @1
            ```java
            line1\r\nline2-new
            ```
            
            BRK_EXCERPT_5
            file.java @3
            ```java
            line3
            ```
            """;

        var stubModel = new TestScriptedLanguageModel(resp1);
        var llm = new Llm(stubModel, "test", cm, false, false, false, false);
        var initialMessages = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
        initialMessages.add(new dev.langchain4j.data.message.UserMessage("analyze"));
        var turn1Result = llm.sendRequest(initialMessages);

        Map<Integer, CodeExcerpt> result = agent.retryInStages(llm, new ArrayList<>(), turn1Result);

        assertEquals(2, result.size());
        assertEquals(1, result.get(0).line());
        assertEquals(3, result.get(5).line());
        
        // 3. Stage 1 passes (file exists), but Stage 2 fails (content doesn't match)
        String mismatchContent = """
            BRK_EXCERPT_10
            file.java @1
            ```java
            no-match-here
            ```
            """;
        String fixContent = """
            BRK_EXCERPT_10
            file.java @4
            ```java
            line4
            ```
            """;
            
        var stage2Model = new TestScriptedLanguageModel(mismatchContent, fixContent);
        var stage2Llm = new Llm(stage2Model, "test", cm, false, false, false, false);
        var t1Result = stage2Llm.sendRequest(initialMessages);

        Map<Integer, CodeExcerpt> result2 = agent.retryInStages(stage2Llm, new ArrayList<>(), t1Result);
        assertEquals(1, result2.size());
        assertEquals(4, result2.get(10).line());
    }

    @Test
    void testRetryInStages_preservesPartialSuccessOnLlmError() throws InterruptedException, IOException {
        Files.writeString(tempDir.resolve("file1.java"), "content1");
        TestProject project = new TestProject(tempDir);
        IContextManager cm = new TestContextManager(project);
        
        var info = new FileComparisonInfo(
            null, 
            new BufferSource.StringSource("content1", "OLD", "file1.java"),
            new BufferSource.StringSource("content1", "NEW", "file1.java")
        );
        
        ReviewAgent agent = new ReviewAgent("diff", cm, null, List.of(info));

        // First response has one good and one bad
        String resp1 = """
            BRK_EXCERPT_0
            file1.java @1
            ```java
            content1
            ```
            BRK_EXCERPT_1
            missing.java @1
            ```java
            fail
            ```
            """;

        // Second response would fix it, but we'll simulate a network error via a custom model if needed,
        // or just rely on the fact that if sendRequest returned an error, the loop breaks.
        // For this test, we can use a model that returns a string, but we want the LLM to report an error.
        
        // Since TestScriptedLanguageModel doesn't easily simulate errors, 
        // we'll just test that whatever was resolved BEFORE the retry is kept.
        var stubModel = new TestScriptedLanguageModel(resp1); 
        // We'll mock the Llm to return an error on the second call
        Llm errorLlm = new Llm(stubModel, "test", cm, false, false, false, false) {
            private int calls = 0;
            @Override
            public StreamingResult sendRequest(List<dev.langchain4j.data.message.ChatMessage> messages) {
                if (calls++ > 0) return new StreamingResult(null, new RuntimeException("LLM Down"));
                try {
                    return super.sendRequest(messages);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        var initialMessages = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
        initialMessages.add(new dev.langchain4j.data.message.UserMessage("analyze"));
        var turn1Result = errorLlm.sendRequest(initialMessages);

        Map<Integer, CodeExcerpt> result = agent.retryInStages(errorLlm, new ArrayList<>(), turn1Result);

        assertEquals(1, result.size());
        assertTrue(result.containsKey(0));
    }
}
