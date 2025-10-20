package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.testutil.TestConsoleIO;
import io.github.jbellis.brokk.testutil.TestContextManager;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.Messages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeAgentTest {

    private static class CountingPreprocessorModel implements StreamingChatModel {
        private final AtomicInteger preprocessingCallCount = new AtomicInteger(0);
        private final String cannedResponse;

        CountingPreprocessorModel(String cannedResponse) {
            this.cannedResponse = cannedResponse;
        }

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            // Check if this is a preprocessing request by looking for the distinctive system message
            boolean isPreprocessingRequest = chatRequest.messages().stream().anyMatch(msg -> {
                String text = Messages.getText(msg);
                return text != null && text.contains("You are familiar with common build and lint tools");
            });

            if (isPreprocessingRequest) {
                preprocessingCallCount.incrementAndGet();
            }

            handler.onPartialResponse(cannedResponse);
            var cr = ChatResponse.builder()
                    .aiMessage(new AiMessage(cannedResponse))
                    .build();
            handler.onCompleteResponse(cr);
        }

        int getPreprocessingCallCount() {
            return preprocessingCallCount.get();
        }
    }

    @TempDir
    Path projectRoot;

    TestContextManager contextManager;
    TestConsoleIO consoleIO;
    CodeAgent codeAgent;
    EditBlockParser parser;
    BiFunction<String, Path, Environment.ShellCommandRunner> originalShellCommandRunnerFactory;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(projectRoot);
        consoleIO = new TestConsoleIO();
        contextManager = new TestContextManager(projectRoot, consoleIO);
        // For tests not needing LLM, model can be a dummy,
        // as CodeAgent's constructor doesn't use it directly.
        // Llm instance creation is deferred to runTask/runQuickTask.
        codeAgent = new CodeAgent(contextManager, new Service.UnavailableStreamingModel(), consoleIO);
        parser = EditBlockParser.instance; // Basic parser

        // Save original shell command runner factory
        originalShellCommandRunnerFactory = Environment.shellCommandRunnerFactory;
    }

    @AfterEach
    void tearDown() {
        // Restore original shell command runner factory
        Environment.shellCommandRunnerFactory = originalShellCommandRunnerFactory;
    }

    protected CodeAgent.ConversationState createConversationState(
            List<ChatMessage> taskMessages, UserMessage nextRequest) {
        return new CodeAgent.ConversationState(new ArrayList<>(taskMessages), nextRequest, taskMessages.size());
    }

    private CodeAgent.EditState createEditState(
            List<EditBlock.SearchReplaceBlock> pendingBlocks, int blocksAppliedWithoutBuild) {
        return new CodeAgent.EditState(
                new ArrayList<>(pendingBlocks), // Modifiable copy
                0, // consecutiveParseFailures
                0, // consecutiveApplyFailures
                0, // consecutiveBuildFailures
                blocksAppliedWithoutBuild,
                "", // lastBuildError
                new HashSet<>(), // changedFiles
                new HashMap<>(), // originalFileContents
                Collections.emptyMap() // javaLintDiagnostics
                );
    }

    private CodeAgent.ConversationState createBasicConversationState() {
        return createConversationState(List.of(), new UserMessage("test request"));
    }

    // P-1: parsePhase – prose-only response (not an error)
    @Test
    void testParsePhase_proseOnlyResponseIsNotError() {
        var cs = createBasicConversationState();
        var es = createEditState(List.of(), 0);
        // This input contains no blocks and should be treated as a successful, empty parse.
        String proseOnlyText = "Okay, I will make the changes now.";

        var result = codeAgent.parsePhase(cs, es, proseOnlyText, false, parser, null);

        // A prose-only response is not a parse error; it should result in a Continue step.
        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;
        assertEquals(0, continueStep.es().consecutiveParseFailures());
        assertTrue(continueStep.es().pendingBlocks().isEmpty());
    }

    // P-2: parsePhase – partial parse + error
    @Test
    void testParsePhase_partialParseWithError() {
        var cs = createBasicConversationState();
        var es = createEditState(List.of(), 0);
        // A valid block followed by malformed text. The lenient parser should find
        // the first block and then stop without reporting an error.
        String llmText =
                """
                         <block>
                         file.java
                         <<<<<<< SEARCH
                         System.out.println("Hello");
                         =======
                         System.out.println("World");
                         >>>>>>> REPLACE
                         </block>
                         This is some trailing text.
                         """;

        var result = codeAgent.parsePhase(cs, es, llmText, false, parser, null);

        // The parser is lenient; it finds the valid block and ignores the rest.
        // This is not a parse error, so we continue.
        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;
        assertEquals(0, continueStep.es().consecutiveParseFailures());
        assertEquals(1, continueStep.es().pendingBlocks().size(), "One block should be parsed and now pending.");
    }

    // P-3: parsePhase - pure parse error, should retry with reminder
    @Test
    void testParsePhase_pureParseError_replacesLastRequest() {
        var originalRequest = new UserMessage("original user request");
        String llmTextWithParseError =
                """
                <block>
                file.java
                <<<<<<< SEARCH
                foo();
                >>>>>>> REPLACE
                </block>
                """; // Missing ======= divider
        var badAiResponse = new AiMessage(llmTextWithParseError);

        // Set up a conversation history. The state before parsePhase would have the last request and the bad response.
        var taskMessages = new ArrayList<ChatMessage>();
        taskMessages.add(new UserMessage("some earlier message"));
        taskMessages.add(originalRequest);
        taskMessages.add(badAiResponse);

        var cs = new CodeAgent.ConversationState(taskMessages, new UserMessage("placeholder"), taskMessages.size());
        var es = createEditState(List.of(), 0);

        // Act
        var result = codeAgent.parsePhase(cs, es, llmTextWithParseError, false, parser, null);

        // Assert
        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        var newCs = retryStep.cs();

        assertEquals(1, retryStep.es().consecutiveParseFailures());

        // Check conversation history was modified
        var finalTaskMessages = newCs.taskMessages();
        assertEquals(1, finalTaskMessages.size());
        assertEquals("some earlier message", Messages.getText(finalTaskMessages.getFirst()));

        // Check the new 'nextRequest'
        String nextRequestText = Messages.getText(requireNonNull(newCs.nextRequest()));
        assertTrue(nextRequestText.contains("original user request"));
        assertTrue(nextRequestText.contains(
                "Remember to pay close attention to the SEARCH/REPLACE block format instructions and examples!"));
    }

    // P-3a: parsePhase – isPartial flag handling (with zero blocks)
    @Test
    void testParsePhase_isPartial_zeroBlocks() {
        var cs = createBasicConversationState();
        var es = createEditState(List.of(), 0);
        String llmTextNoBlocks = "Thinking...";

        var result = codeAgent.parsePhase(cs, es, llmTextNoBlocks, true, parser, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertTrue(Messages.getText(requireNonNull(retryStep.cs().nextRequest()))
                .contains("cut off before you provided any code blocks"));
        assertTrue(retryStep.es().pendingBlocks().isEmpty());
    }

    // P-3b: parsePhase – isPartial flag handling (with >=1 block)
    @Test
    void testParsePhase_isPartial_withBlocks() {
        var cs = createBasicConversationState();
        var es = createEditState(List.of(), 0);
        String llmTextWithBlock =
                """
                                  <block>
                                  file.java
                                  <<<<<<< SEARCH
                                  System.out.println("Hello");
                                  =======
                                  System.out.println("World");
                                  >>>>>>> REPLACE
                                  </block>
                                  """;

        var result = codeAgent.parsePhase(cs, es, llmTextWithBlock, true, parser, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertTrue(
                Messages.getText(requireNonNull(retryStep.cs().nextRequest())).contains("continue from there"));
        assertEquals(1, retryStep.es().pendingBlocks().size());
    }

    // A-2: applyPhase – total apply failure (below fallback threshold)
    @Test
    void testApplyPhase_totalApplyFailure_belowThreshold() throws IOException {
        var file = contextManager.toFile("test.txt");
        file.write("initial content");
        contextManager.addEditableFile(file);

        var nonMatchingBlock =
                new EditBlock.SearchReplaceBlock(file.toString(), "text that does not exist", "replacement");
        var cs = createConversationState(List.of(), new UserMessage("req"));
        var es = createEditState(List.of(nonMatchingBlock), 0);

        var result = codeAgent.applyPhase(cs, es, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertEquals(1, retryStep.es().consecutiveApplyFailures());
        assertEquals(0, retryStep.es().blocksAppliedWithoutBuild());
        String nextRequestText = Messages.getText(requireNonNull(retryStep.cs().nextRequest()));
        // check that the name of the file that failed to apply is mentioned in the retry prompt.
        assertTrue(nextRequestText.contains(file.getFileName()));
    }

    // A-4: applyPhase – mix success & failure
    @Test
    void testApplyPhase_mixSuccessAndFailure() throws IOException {
        var file1 = contextManager.toFile("file1.txt");
        file1.write("hello world");
        contextManager.addEditableFile(file1);

        var file2 = contextManager.toFile("file2.txt");
        file2.write("foo bar");
        contextManager.addEditableFile(file2);

        // This block will succeed because it matches the full line content
        var successBlock = new EditBlock.SearchReplaceBlock(file1.toString(), "hello world", "goodbye world");
        var failureBlock = new EditBlock.SearchReplaceBlock(file2.toString(), "nonexistent", "text");

        var cs = createConversationState(List.of(), new UserMessage("req"));
        var es = createEditState(List.of(successBlock, failureBlock), 0);

        var result = codeAgent.applyPhase(cs, es, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;

        // On partial success, consecutive failures should reset, and applied count should increment.
        assertEquals(
                0, retryStep.es().consecutiveApplyFailures(), "Consecutive failures should reset on partial success");
        assertEquals(1, retryStep.es().blocksAppliedWithoutBuild(), "One block should have been applied");

        // The retry message should reflect both the success and the failure.
        String nextRequestText = Messages.getText(requireNonNull(retryStep.cs().nextRequest()));
        // Weaker assertion: just check that the name of the file that failed to apply is mentioned.
        assertTrue(nextRequestText.contains(file2.getFileName()));

        // Verify the successful edit was actually made.
        assertEquals("goodbye world", file1.read().orElseThrow().strip());
    }

    // V-1: verifyPhase – skip when no edits
    @Test
    void testVerifyPhase_skipWhenNoEdits() {
        var cs = createConversationState(List.of(new AiMessage("no edits")), new UserMessage("test request"));
        var es = createEditState(List.of(), 0);
        var result = codeAgent.verifyPhase(cs, es, null);

        assertInstanceOf(CodeAgent.Step.Fatal.class, result);
        var step = (CodeAgent.Step.Fatal) result;
        assertEquals(TaskResult.StopReason.SUCCESS, step.stopDetails().reason());
    }

    // V-2: verifyPhase – verification command absent
    @Test
    void testVerifyPhase_verificationCommandAbsent() {
        contextManager.getProject().setBuildDetails(BuildAgent.BuildDetails.EMPTY); // No commands
        var cs = createConversationState(List.of(), new UserMessage("req"));
        var es = createEditState(List.of(), 1); // 1 block applied

        var result = codeAgent.verifyPhase(cs, es, null);

        assertInstanceOf(CodeAgent.Step.Fatal.class, result);
        var step = (CodeAgent.Step.Fatal) result;
        assertEquals(TaskResult.StopReason.SUCCESS, step.stopDetails().reason());
    }

    // V-3: verifyPhase – build failure loop (mocking Environment.runShellCommand)
    @Test
    void testVerifyPhase_buildFailureAndSuccessCycle() {
        var bd = new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test {{files}}", Set.of());
        contextManager.getProject().setBuildDetails(bd);
        contextManager.getProject().setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL); // to use testAllCommand

        var attempt = new AtomicInteger(0);
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            int currentAttempt = attempt.getAndIncrement();
            // Log the attempt to help diagnose mock behavior using a more visible marker
            System.out.println(
                    "[TEST DEBUG] MockShellCommandRunner: Attempt " + currentAttempt + " for command: " + cmd);
            outputConsumer.accept("MockShell: attempt " + currentAttempt + " for command: " + cmd);
            if (currentAttempt == 0) { // First attempt fails
                outputConsumer.accept("Build error line 1");
                throw new Environment.FailureException("Build failed", "Detailed build error output");
            }
            // Second attempt (or subsequent if MAX_BUILD_FAILURES > 1) succeeds
            outputConsumer.accept("Build successful");
            return "Successful output";
        };

        var cs = createConversationState(List.of(), new UserMessage("req"));
        var es = createEditState(List.of(), 1); // 1 block applied

        // First run - build should fail
        var resultFail = codeAgent.verifyPhase(cs, es, null);
        assertInstanceOf(CodeAgent.Step.Retry.class, resultFail);
        var retryStep = (CodeAgent.Step.Retry) resultFail;
        assertTrue(retryStep.es().lastBuildError().contains("Detailed build error output"));
        assertEquals(0, retryStep.es().blocksAppliedWithoutBuild()); // Reset
        assertTrue(
                Messages.getText(requireNonNull(retryStep.cs().nextRequest())).contains("The build failed"));

        // Second run - build should succeed
        // We must manually create a new state that simulates new edits having been applied,
        // otherwise verifyPhase will short-circuit because blocksAppliedWithoutBuild is 0 from the Retry step.
        var cs2 = retryStep.cs();
        var es2 = new CodeAgent.EditState(
                List.of(), // pending blocks are empty
                retryStep.es().consecutiveParseFailures(),
                retryStep.es().consecutiveApplyFailures(),
                retryStep.es().consecutiveBuildFailures(),
                1, // Simulate one new fix was applied to pass the guard in verifyPhase
                retryStep.es().lastBuildError(),
                retryStep.es().changedFiles(),
                retryStep.es().originalFileContents(),
                retryStep.es().javaLintDiagnostics());

        var resultSuccess = codeAgent.verifyPhase(cs2, es2, null);
        assertInstanceOf(CodeAgent.Step.Fatal.class, resultSuccess);
        var step = (CodeAgent.Step.Fatal) resultSuccess;
        assertEquals(TaskResult.StopReason.SUCCESS, step.stopDetails().reason());
    }

    // INT-1: Interruption during verifyPhase (via Environment stub)
    @Test
    void testVerifyPhase_interruptionDuringBuild() {
        var bd = new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test {{files}}", Set.of());
        contextManager.getProject().setBuildDetails(bd);
        contextManager.getProject().setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            throw new InterruptedException("Simulated interruption during shell command");
        };

        var cs = createConversationState(List.of(), new UserMessage("req"));
        var es = createEditState(List.of(), 1);

        var result = codeAgent.verifyPhase(cs, es, null);
        assertInstanceOf(CodeAgent.Step.Fatal.class, result);
        var fatalStep = (CodeAgent.Step.Fatal) result;
        assertEquals(TaskResult.StopReason.INTERRUPTED, fatalStep.stopDetails().reason());
    }

    // L-1: Loop termination - "no edits, no error"
    @Test
    void testRunTask_exitsSuccessOnNoEdits() {
        var stubModel = new TestScriptedLanguageModel("Okay, I see no changes are needed.");
        codeAgent = new CodeAgent(contextManager, stubModel, consoleIO);
        contextManager.getProject().setBuildDetails(BuildAgent.BuildDetails.EMPTY); // No build command

        var result = codeAgent.runTask("A request that results in no edits", Set.of());

        assertEquals(TaskResult.StopReason.SUCCESS, result.stopDetails().reason());
        assertTrue(result.changedFiles().isEmpty());
    }

    // L-2: Loop termination - "no edits, but has build error"
    @Test
    void testRunTask_exitsBuildErrorOnNoEditsWithPreviousError() throws IOException {
        // Script:
        // 1. LLM provides a valid edit.
        // 2. Build fails. Loop retries with build error in prompt.
        // 3. LLM provides no more edits ("I give up").
        // 4. Loop terminates with BUILD_ERROR.

        var file = contextManager.toFile("test.txt");
        file.write("hello");
        contextManager.addEditableFile(file);

        var firstResponse =
                """
                            <block>
                            test.txt
                            <<<<<<< SEARCH
                            hello
                            =======
                            goodbye
                            >>>>>>> REPLACE
                            </block>
                            """;
        var secondResponse = "I am unable to fix the build error.";
        var stubModel = new TestScriptedLanguageModel(firstResponse, secondResponse);

        // Make the build command fail once
        var buildAttempt = new AtomicInteger(0);
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            if (buildAttempt.getAndIncrement() == 0) {
                throw new Environment.FailureException("Build failed", "Compiler error on line 5");
            }
            return "Build successful";
        };

        var bd = new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test", Set.of());
        contextManager.getProject().setBuildDetails(bd);
        contextManager.getProject().setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        codeAgent = new CodeAgent(contextManager, stubModel, consoleIO);
        var result = codeAgent.runTask("change hello to goodbye", Set.of());

        assertEquals(TaskResult.StopReason.BUILD_ERROR, result.stopDetails().reason());
        assertTrue(result.stopDetails().explanation().contains("Compiler error on line 5"));
        assertEquals("goodbye", file.read().orElseThrow().strip()); // The edit was made and not reverted
    }

    // CF-1: changedFiles tracking after successful apply
    @Test
    void testApplyPhase_updatesChangedFilesSet() throws IOException {
        var file = contextManager.toFile("file.txt");
        file.write("old");
        contextManager.addEditableFile(file);

        var block = new EditBlock.SearchReplaceBlock(file.toString(), "old", "new");
        var cs = createConversationState(List.of(), new UserMessage("req"));
        var es = createEditState(List.of(block), 0);

        var result = codeAgent.applyPhase(cs, es, null);

        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;
        assertTrue(continueStep.es().changedFiles().contains(file), "changedFiles should include the edited file");
    }

    // S-1: verifyPhase sanitizes Unix Java-style compiler output
    @Test
    void testVerifyPhase_sanitizesUnixJavaPaths() {
        var bd = new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test {{files}}", Set.of());
        contextManager.getProject().setBuildDetails(bd);
        contextManager.getProject().setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        var rootFwd = projectRoot.toAbsolutePath().toString().replace('\\', '/');
        var absPath = rootFwd + "/src/Main.java";
        var errorOutput = absPath + ":12: error: cannot find symbol\n    Foo bar;\n    ^\n1 error\n";

        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            throw new Environment.FailureException("Build failed", errorOutput);
        };

        var cs = createConversationState(List.of(), new UserMessage("req"));
        var es = createEditState(List.of(), 1);
        var result = codeAgent.verifyPhase(cs, es, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retry = (CodeAgent.Step.Retry) result;
        var sanitized = retry.es().lastBuildError();

        assertFalse(sanitized.contains(rootFwd), "Sanitized output should not contain absolute root");
        assertTrue(sanitized.contains("src/Main.java:12"), "Sanitized output should contain relativized path");
    }

    // S-2: verifyPhase sanitizes Windows Java-style compiler output
    @Test
    void testVerifyPhase_sanitizesWindowsJavaPaths() {
        var bd = new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test {{files}}", Set.of());
        contextManager.getProject().setBuildDetails(bd);
        contextManager.getProject().setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        var rootAbs = projectRoot.toAbsolutePath().toString();
        var rootBwd = rootAbs.replace('/', '\\');
        var absWinPath = rootBwd + "\\src\\Main.java";
        var errorOutput = absWinPath + ":12: error: cannot find symbol\r\n    Foo bar;\r\n    ^\r\n1 error\r\n";

        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            throw new Environment.FailureException("Build failed", errorOutput);
        };

        var cs = createConversationState(List.of(), new UserMessage("req"));
        var es = createEditState(List.of(), 1);
        var result = codeAgent.verifyPhase(cs, es, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retry = (CodeAgent.Step.Retry) result;
        var sanitized = retry.es().lastBuildError();

        assertFalse(sanitized.contains(rootBwd), "Sanitized traceback should not contain absolute Windows root");
        assertTrue(sanitized.contains("src\\Main.java:12"), "Sanitized output should contain relativized Windows path");
    }

    // S-3: verifyPhase sanitizes Python-style traceback paths
    @Test
    void testVerifyPhase_sanitizesPythonTracebackPaths() {
        var bd = new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test {{files}}", Set.of());
        contextManager.getProject().setBuildDetails(bd);
        contextManager.getProject().setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        var rootFwd = projectRoot.toAbsolutePath().toString().replace('\\', '/');
        var absPyPath = rootFwd + "/pkg/mod.py";
        var traceback = ""
                + "Traceback (most recent call last):\n"
                + "  File \"" + absPyPath + "\", line 13, in <module>\n"
                + "    main()\n"
                + "  File \"" + absPyPath + "\", line 8, in main\n"
                + "    raise ValueError(\"bad\")\n"
                + "ValueError: bad\n";

        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            throw new Environment.FailureException("Build failed", traceback);
        };

        var cs = createConversationState(List.of(), new UserMessage("req"));
        var es = createEditState(List.of(), 1);
        var result = codeAgent.verifyPhase(cs, es, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retry = (CodeAgent.Step.Retry) result;
        var sanitized = retry.es().lastBuildError();

        assertFalse(sanitized.contains(rootFwd), "Sanitized traceback should not contain absolute root");
        assertTrue(sanitized.contains("pkg/mod.py"), "Sanitized traceback should contain relativized path");
    }

    // SRB-1: Generate SRBs from per-turn baseline; verify two-turn baseline behavior
    @Test
    void testGenerateSearchReplaceBlocksFromTurn_preservesBaselinePerTurn() throws IOException {
        var file = contextManager.toFile("file.txt");
        file.write("hello world");
        contextManager.addEditableFile(file);

        // Turn 1: apply "hello world" -> "goodbye world"
        var block1 = new EditBlock.SearchReplaceBlock(file.toString(), "hello world", "goodbye world");
        var es1 = new CodeAgent.EditState(
                new ArrayList<>(List.of(block1)),
                0,
                0,
                0,
                0,
                "",
                new HashSet<>(),
                new HashMap<>(),
                Collections.emptyMap());
        var res1 = codeAgent.applyPhase(createConversationState(List.of(), new UserMessage("req1")), es1, null);
        assertInstanceOf(CodeAgent.Step.Continue.class, res1);
        var es1b = ((CodeAgent.Step.Continue) res1).es();

        // Generate SRBs for turn 1; should be hello -> goodbye
        var srb1 = es1b.toSearchReplaceBlocks();
        assertEquals(1, srb1.size());
        assertEquals("hello world", srb1.getFirst().beforeText().strip());
        assertEquals("goodbye world", srb1.getFirst().afterText().strip());

        // Turn 2 baseline should be the current contents ("goodbye world")
        // Prepare next turn state with empty per-turn baseline and a new change: "goodbye world" -> "ciao world"
        var block2 = new EditBlock.SearchReplaceBlock(file.toString(), "goodbye world", "ciao world");
        var es2 = new CodeAgent.EditState(
                new ArrayList<>(List.of(block2)),
                0,
                0,
                0,
                0,
                "",
                new HashSet<>(),
                new HashMap<>(),
                Collections.emptyMap());
        var res2 = codeAgent.applyPhase(createConversationState(List.of(), new UserMessage("req2")), es2, null);
        assertInstanceOf(CodeAgent.Step.Continue.class, res2);
        var es2b = ((CodeAgent.Step.Continue) res2).es();

        var srb2 = es2b.toSearchReplaceBlocks();
        assertEquals(1, srb2.size());
        assertEquals("goodbye world", srb2.getFirst().beforeText().strip());
        assertEquals("ciao world", srb2.getFirst().afterText().strip());
    }

    // SRB-2: Multiple distinct changes in a single turn produce multiple S/R blocks (fine-grained)
    @Test
    void testGenerateSearchReplaceBlocksFromTurn_multipleChangesProduceMultipleBlocks() throws IOException {
        var file = contextManager.toFile("multi.txt");
        var original = String.join("\n", List.of("alpha", "keep", "omega")) + "\n";
        file.write(original);
        contextManager.addEditableFile(file);

        // Prepare per-turn baseline manually (simulate what applyPhase would capture)
        var originalMap = new HashMap<ProjectFile, String>();
        originalMap.put(file, original);
        var changedFiles = new HashSet<ProjectFile>();
        changedFiles.add(file);

        // Modify two separate lines: alpha->ALPHA and omega->OMEGA
        var revised = String.join("\n", List.of("ALPHA", "keep", "OMEGA")) + "\n";
        file.write(revised);

        var es = new CodeAgent.EditState(
                List.of(), // pending blocks
                0,
                0,
                0,
                1, // blocksAppliedWithoutBuild (not relevant for generation)
                "", // lastBuildError
                changedFiles,
                originalMap,
                Collections.emptyMap());

        var blocks = es.toSearchReplaceBlocks();
        // Expect two distinct blocks (one per changed line)
        assertTrue(blocks.size() >= 2, "Expected multiple fine-grained S/R blocks");

        var normalized = blocks.stream()
                .map(b -> Map.entry(b.beforeText().strip(), b.afterText().strip()))
                .toList();

        assertTrue(normalized.contains(Map.entry("alpha", "ALPHA")));
        assertTrue(normalized.contains(Map.entry("omega", "OMEGA")));
    }

    // SRB-3: Ensure expansion to achieve uniqueness (avoid ambiguous search blocks)
    @Test
    void testGenerateSearchReplaceBlocksFromTurn_expandsToUniqueSearchTargets() throws IOException {
        var file = contextManager.toFile("unique.txt");
        var original = String.join("\n", List.of("alpha", "beta", "alpha", "gamma")) + "\n";
        file.write(original);
        contextManager.addEditableFile(file);

        var originalMap = new HashMap<ProjectFile, String>();
        originalMap.put(file, original);
        var changedFiles = new HashSet<ProjectFile>();
        changedFiles.add(file);

        // Change the second "alpha" only
        var revised = String.join("\n", List.of("alpha", "beta", "ALPHA", "gamma")) + "\n";
        file.write(revised);

        var es = new CodeAgent.EditState(List.of(), 0, 0, 0, 1, "", changedFiles, originalMap, Collections.emptyMap());

        var blocks = es.toSearchReplaceBlocks();
        assertEquals(1, blocks.size(), "Should produce a single unique block");
        var before = blocks.getFirst().beforeText();
        // Ensure we didn't emit a bare "alpha" which would be ambiguous; context should be included
        assertNotEquals("alpha\n", before, "Search should be expanded with context to be unique");
        assertTrue(before.contains("beta"), "Expanded context should likely include neighboring lines");
    }

    // SRB-4: Overlapping expansions should merge into a single block
    @Test
    void testGenerateSearchReplaceBlocksFromTurn_mergesOverlappingExpansions() throws IOException {
        var file = contextManager.toFile("merge.txt");
        var original = String.join("\n", List.of("line1", "target", "middle", "target", "line5")) + "\n";
        file.write(original);
        contextManager.addEditableFile(file);

        var originalMap = new HashMap<ProjectFile, String>();
        originalMap.put(file, original);
        var changedFiles = new HashSet<ProjectFile>();
        changedFiles.add(file);

        // Change both 'target' lines
        var revised = String.join("\n", List.of("line1", "TARGET", "middle", "TARGET", "line5")) + "\n";
        file.write(revised);

        var es = new CodeAgent.EditState(List.of(), 0, 0, 0, 1, "", changedFiles, originalMap, Collections.emptyMap());

        var blocks = es.toSearchReplaceBlocks();

        // Because uniqueness expansion will expand both to include 'middle' neighbor,
        // overlapping regions should merge into one block.
        assertEquals(1, blocks.size(), "Overlapping expanded regions should be merged");
        var b = blocks.getFirst();
        assertTrue(
                b.beforeText().contains("target\nmiddle\ntarget"), "Merged before should span both targets and middle");
        assertTrue(b.afterText().contains("TARGET\nmiddle\nTARGET"), "Merged after should reflect both changes");
    }

    // TURN-1: replaceCurrentTurnMessages should replace the entire turn, not just last two messages
    @Test
    void testReplaceCurrentTurnMessages_replacesWholeTurn() {
        var msgs = new ArrayList<ChatMessage>();
        msgs.add(new UserMessage("old turn user"));
        msgs.add(new AiMessage("old turn ai"));

        // Start of new turn at index 2
        int turnStart = msgs.size();
        msgs.add(new UserMessage("turn start"));
        msgs.add(new AiMessage("partial response 1"));
        msgs.add(new UserMessage("retry prompt"));
        msgs.add(new AiMessage("partial response 2"));

        var cs = new CodeAgent.ConversationState(msgs, new UserMessage("next request"), turnStart);
        var summary = "Here are the SEARCH/REPLACE blocks:\n\n<summary>";
        var replaced = cs.replaceCurrentTurnMessages(summary);

        var finalMsgs = replaced.taskMessages();
        // We should have: [old turn user, old turn ai, turn start (user), summary (ai)]
        assertEquals(4, finalMsgs.size());
        assertEquals("turn start", Messages.getText(finalMsgs.get(2)));
        assertEquals("Here are the SEARCH/REPLACE blocks:\n\n<summary>", ((AiMessage) finalMsgs.get(3)).text());
        // Next turn should start at end
        assertEquals(finalMsgs.size(), replaced.turnStartIndex());
    }

    // verifyPhase should call BuildOutputPreprocessor.processForLlm only once, not twice
    @Test
    void testVerifyPhase_callsProcessForLlmOnlyOnce() {
        // Setup: Create a counting model that tracks preprocessing requests
        var cannedPreprocessedOutput = "Error in file.java:10: syntax error";
        var countingModel = new CountingPreprocessorModel(cannedPreprocessedOutput);

        // Configure the context manager to use the counting model for quickest model
        contextManager.setQuickestModel(countingModel);

        // Configure build to fail with output that exceeds threshold (> 200 lines)
        var bd = new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test", Set.of());
        contextManager.getProject().setBuildDetails(bd);
        contextManager.getProject().setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        // Generate long build output (> 200 lines to trigger LLM preprocessing)
        StringBuilder longOutput = new StringBuilder();
        for (int i = 1; i <= 210; i++) {
            longOutput.append("Error line ").append(i).append("\n");
        }

        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            throw new Environment.FailureException("Build failed", longOutput.toString());
        };

        var cs = createConversationState(List.of(), new UserMessage("req"));
        var es = createEditState(List.of(), 1); // 1 block applied to trigger verification

        // Act: Run verifyPhase which should process build output
        var result = codeAgent.verifyPhase(cs, es, null);

        // Assert: Should be a retry with build error
        assertInstanceOf(CodeAgent.Step.Retry.class, result);

        // Assert: processForLlm should be called exactly once by BuildAgent
        // CodeAgent retrieves the processed output from BuildFragment instead of reprocessing
        assertEquals(
                1,
                countingModel.getPreprocessingCallCount(),
                "BuildOutputPreprocessor.processForLlm should only be called once per build failure "
                        + "(by BuildAgent), but was called " + countingModel.getPreprocessingCallCount() + " times");
    }

    // REQ-1: requestPhase with partial response + error should continue, not exit fatally
    @Test
    void testRequestPhase_partialResponseWithTransportError_shouldContinueAndLetParseHandle() {
        // Create a model that returns partial text (some valid blocks) + error (simulating connection drop)
        var partialBlockText =
                """
                <block>
                test.txt
                <<<<<<< SEARCH
                hello
                =======
                goodbye
                >>>>>>> REPLACE
                </block>
                """;

        // Prepare the Llm.StreamingResult with partial text and an error.
        // Note: when error is non-null, originalResponse must be null (it's a synthetic partial response)
        var partialResponse = new Llm.NullSafeResponse(
                partialBlockText, // text
                null, // reasoningContent
                List.of(), // toolRequests
                null); // originalResponse must be null when paired with an error
        var streamingResult = new Llm.StreamingResult(partialResponse, new RuntimeException("Connection reset"), 0);

        var cs = createBasicConversationState();
        var es = createEditState(List.of(), 0);

        // Act: Call requestPhase with the partial + error result
        var result = codeAgent.requestPhase(cs, es, streamingResult, null);

        // Assert: Should continue (not fatal), so that parsePhase can handle the partial
        assertInstanceOf(
                CodeAgent.Step.Continue.class,
                result,
                "requestPhase should continue when partial text is present, even with error");
        var continueStep = (CodeAgent.Step.Continue) result;

        // The request and AI message should be appended
        assertEquals(2, continueStep.cs().taskMessages().size());
        String aiMessageText = Messages.getText(continueStep.cs().taskMessages().get(1));
        assertTrue(aiMessageText.contains("goodbye"), "AI message should contain the partial block content");

        // nextRequest should be null after sending (Task 3 semantics)
        assertNull(continueStep.cs().nextRequest(), "nextRequest should be null after recording");
    }
}
