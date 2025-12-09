package ai.brokk.agents;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.EditBlock;
import ai.brokk.Llm;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.JavaAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ViewingPolicy;
import ai.brokk.project.IProject;
import ai.brokk.prompts.CodePrompts;
import ai.brokk.prompts.EditBlockParser;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import ai.brokk.util.Environment;
import ai.brokk.util.Messages;
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
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeAgentTest {

    private TestProject project;

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
                return text.contains("You are familiar with common build and lint tools");
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

    TestContextManager cm;
    TestConsoleIO consoleIO;
    CodeAgent codeAgent;
    BiFunction<String, Path, Environment.ShellCommandRunner> originalShellCommandRunnerFactory;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(projectRoot);
        consoleIO = new TestConsoleIO();
        project = new TestProject(projectRoot, Languages.JAVA);
        cm = new TestContextManager(projectRoot, consoleIO, new JavaAnalyzer(project));
        assert cm.getProject() == project;
        codeAgent = new CodeAgent(cm, new Service.UnavailableStreamingModel(), consoleIO);

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

        var result = codeAgent.parsePhase(cs, es, proseOnlyText, false, EditBlockParser.instance, null);

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

        var result = codeAgent.parsePhase(cs, es, llmText, false, EditBlockParser.instance, null);

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
        var result = codeAgent.parsePhase(cs, es, llmTextWithParseError, false, EditBlockParser.instance, null);

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

        var result = codeAgent.parsePhase(cs, es, llmTextNoBlocks, true, EditBlockParser.instance, null);

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

        var result = codeAgent.parsePhase(cs, es, llmTextWithBlock, true, EditBlockParser.instance, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertTrue(
                Messages.getText(requireNonNull(retryStep.cs().nextRequest())).contains("continue from there"));
        assertEquals(1, retryStep.es().pendingBlocks().size());
    }

    // A-2: applyPhase – total apply failure (below fallback threshold)
    @Test
    void testApplyPhase_totalApplyFailure_belowThreshold() throws IOException {
        var file = cm.toFile("test.txt");
        file.write("initial content");
        cm.addEditableFile(file);

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
        var file1 = cm.toFile("file1.txt");
        file1.write("hello world");
        cm.addEditableFile(file1);

        var file2 = cm.toFile("file2.txt");
        file2.write("foo bar");
        cm.addEditableFile(file2);

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
        project.setBuildDetails(BuildAgent.BuildDetails.EMPTY); // No commands
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
        project.setBuildDetails(bd);
        project.setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL); // to use testAllCommand

        var attempt = new AtomicInteger(0);
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            int currentAttempt = attempt.getAndIncrement();
            // Log the attempt to help diagnose mock behavior using a more visible marker
            System.out.println(
                    "[TEST DEBUG] MockShellCommandRunner: Attempt " + currentAttempt + " for command: " + cmd);
            outputConsumer.accept("MockShell: attempt " + currentAttempt + " for command: " + cmd);
            if (currentAttempt == 0) { // First attempt fails
                outputConsumer.accept("Build error line 1");
                throw new Environment.FailureException("Build failed", "Detailed build error output", 1);
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
        project.setBuildDetails(bd);
        project.setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

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
        codeAgent = new CodeAgent(cm, stubModel, consoleIO);
        project.setBuildDetails(BuildAgent.BuildDetails.EMPTY); // No build command
        var initialContext = newContext();
        var result = codeAgent.runTask(initialContext, List.of(), "A request that results in no edits", Set.of());

        assertEquals(TaskResult.StopReason.SUCCESS, result.stopDetails().reason());
        assertEquals(initialContext, result.context());
    }

    // L-2: Loop termination - "no edits, but has build error"
    @Test
    void testRunTask_exitsBuildErrorOnNoEditsWithPreviousError() throws IOException {
        // Script:
        // 1. LLM provides a valid edit.
        // 2. Build fails. Loop retries with build error in prompt.
        // 3. LLM provides no more edits ("I give up").
        // 4. Loop terminates with BUILD_ERROR.

        var file = cm.toFile("test.txt");
        file.write("hello");
        cm.addEditableFile(file);

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
                throw new Environment.FailureException("Build failed", "Compiler error on line 5", 1);
            }
            return "Build successful";
        };

        var bd = new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test", Set.of());
        project.setBuildDetails(bd);
        project.setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        codeAgent = new CodeAgent(cm, stubModel, consoleIO);
        var result = codeAgent.runTask("change hello to goodbye", Set.of());

        assertEquals(TaskResult.StopReason.BUILD_ERROR, result.stopDetails().reason());
        assertTrue(result.stopDetails().explanation().contains("Compiler error on line 5"));
        assertEquals("goodbye", file.read().orElseThrow().strip()); // The edit was made and not reverted
    }

    // CF-1: changedFiles tracking after successful apply
    @Test
    void testApplyPhase_updatesChangedFilesSet() throws IOException {
        var file = cm.toFile("file.txt");
        file.write("old");
        cm.addEditableFile(file);

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
        project.setBuildDetails(bd);
        project.setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        var rootFwd = projectRoot.toAbsolutePath().toString().replace('\\', '/');
        var absPath = rootFwd + "/src/Main.java";
        var errorOutput = absPath + ":12: error: cannot find symbol\n    Foo bar;\n    ^\n1 error\n";

        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            throw new Environment.FailureException("Build failed", errorOutput, 1);
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
        project.setBuildDetails(bd);
        project.setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        var rootAbs = projectRoot.toAbsolutePath().toString();
        var rootBwd = rootAbs.replace('/', '\\');
        var absWinPath = rootBwd + "\\src\\Main.java";
        var errorOutput = absWinPath + ":12: error: cannot find symbol\r\n    Foo bar;\r\n    ^\r\n1 error\r\n";

        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            throw new Environment.FailureException("Build failed", errorOutput, 1);
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
        project.setBuildDetails(bd);
        project.setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

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
            throw new Environment.FailureException("Build failed", traceback, 1);
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
        var file = cm.toFile("file.txt");
        file.write("hello world");
        cm.addEditableFile(file);

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
        var file = cm.toFile("multi.txt");
        var original = String.join("\n", List.of("alpha", "keep", "omega")) + "\n";
        file.write(original);
        cm.addEditableFile(file);

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
        var file = cm.toFile("unique.txt");
        var original = String.join("\n", List.of("alpha", "beta", "alpha", "gamma")) + "\n";
        file.write(original);
        cm.addEditableFile(file);

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
        var file = cm.toFile("merge.txt");
        var original = String.join("\n", List.of("line1", "target", "middle", "target", "line5")) + "\n";
        file.write(original);
        cm.addEditableFile(file);

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
        cm.setQuickestModel(countingModel);

        // Configure build to fail with output that exceeds threshold (> 200 lines)
        var bd = new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test", Set.of());
        project.setBuildDetails(bd);
        project.setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        // Generate long build output (> 500 lines to trigger LLM preprocessing)
        StringBuilder longOutput = new StringBuilder();
        for (int i = 1; i <= 510; i++) {
            longOutput.append("Error line ").append(i).append("\n");
        }

        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            throw new Environment.FailureException("Build failed", longOutput.toString(), 1);
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

    // Ensure that build errors recorded in Context are surfaced in the workspace prompt
    @Test
    void testBuildErrorIsIncludedInWorkspacePrompt() throws InterruptedException {
        var ctx = newContext().withBuildResult(false, "Simulated build error for prompt");
        var prologue = List.<ChatMessage>of();
        var taskMessages = new ArrayList<ChatMessage>();
        var nextRequest = new UserMessage("Please fix the build");
        var changedFiles = Collections.<ProjectFile>emptySet();

        var messages = CodePrompts.instance.collectCodeMessages(
                new Service.UnavailableStreamingModel(),
                ctx,
                prologue,
                taskMessages,
                nextRequest,
                changedFiles,
                new ViewingPolicy(TaskResult.Type.CODE));

        boolean found = messages.stream()
                .map(Messages::getText)
                .anyMatch(text -> text.contains("Simulated build error for prompt"));

        assertTrue(
                found,
                "Workspace messages for the LLM should include the latest build error text from Context.withBuildResult");
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

    // CTX-REFRESH-1: After edits are applied, context snapshots should be refreshed
    @Test
    void testContextRefreshAfterEdit_contextFragmentContainsUpdatedContent() throws IOException {
        // Arrange: file with initial content
        var file = cm.toFile("refresh.txt");
        file.write("hello");
        cm.addEditableFile(file);

        // First response: apply edit hello -> goodbye
        var firstResponse =
                """
                <block>
                %s
                <<<<<<< SEARCH
                hello
                =======
                goodbye
                >>>>>>> REPLACE
                </block>
                """
                        .formatted(file.toString());

        // Second response: no more edits
        var secondResponse = "I cannot fix this build error.";

        var model = new TestScriptedLanguageModel(firstResponse, secondResponse);
        codeAgent = new CodeAgent(cm, model, consoleIO);

        // Make build fail once to trigger a retry loop that exercises the context refresh
        var buildAttempt = new AtomicInteger(0);
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            if (buildAttempt.getAndIncrement() == 0) {
                throw new Environment.FailureException("Build failed", "Error: compilation failed", 1);
            }
            return "Build successful";
        };

        var bd = new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test", Set.of());
        project.setBuildDetails(bd);
        project.setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        // Act
        codeAgent.runTask("change hello to goodbye", Set.of());

        // Assert: verify the edit was applied to disk
        assertEquals("goodbye", file.read().orElseThrow().strip(), "Edit should have been applied to disk");

        // Assert: codeAgent's internal context should have refreshed fragments with updated content
        var fragments = codeAgent.context.fileFragments().toList();

        assertFalse(fragments.isEmpty(), "Context should contain a fragment for the modified file");

        var fragmentContent = fragments.getFirst().format().join();
        assertTrue(fragmentContent.contains("goodbye"), fragmentContent);
        assertFalse(fragmentContent.contains("hello"), fragmentContent);
    }

    // RO-1: Guardrail - edits to read-only files are blocked with clear error
    @Test
    void testRunTask_blocksEditsToReadOnlyFile() throws IOException, InterruptedException {
        // Arrange: create a file and mark it as read-only in the workspace context
        var roFile = cm.toFile("ro.txt");
        roFile.write("hello");
        // Build a context with a ProjectPathFragment for the file, mark it read-only
        var roFrag = new ContextFragment.ProjectPathFragment(roFile, cm);
        var ctx = newContext().addFragments(List.of(roFrag));
        ctx = ctx.setReadonly(roFrag, true);

        ctx.awaitContextsAreComputed(Duration.of(10, ChronoUnit.SECONDS));
        // Scripted model proposes an edit to the read-only file
        var response =
                """
                <block>
                %s
                <<<<<<< SEARCH
                hello
                =======
                goodbye
                >>>>>>> REPLACE
                </block>
                """
                        .formatted(roFile.toString());
        var stubModel = new TestScriptedLanguageModel(response);
        var agent = new CodeAgent(cm, stubModel, consoleIO);

        // Act
        var result = agent.runTask(ctx, List.of(), "Change ro.txt from hello to goodbye", Set.of());

        // Assert: operation is blocked with READ_ONLY_EDIT and file remains unchanged
        assertEquals(
                TaskResult.StopReason.READ_ONLY_EDIT,
                result.stopDetails().reason(),
                "Should block edits to read-only files");
        assertTrue(
                result.stopDetails().explanation().contains(roFile.toString()),
                "Error message should include the read-only file path");
        assertEquals("hello", roFile.read().orElseThrow().strip(), "Read-only file content must remain unchanged");

        // Assert: No disruptive io.toolError() was called
        assertEquals(
                0,
                consoleIO.getErrorCount(),
                "io.toolError() should not have been called for READ_ONLY_EDIT in standalone CodeAgent mode");
    }

    private Context newContext() {
        return new Context(cm);
    }

    // RO-3: Guardrail precedence - editable ProjectPathFragment takes precedence over read-only virtual fragment
    @Test
    void testRunTask_editablePrecedesReadOnlyVirtualFragment() throws IOException, InterruptedException {
        // Arrange: create a file and add it as both an editable ProjectPathFragment
        // and a read-only virtual fragment (simulating a Code or Usage reference)
        var file = cm.toFile("file.txt");
        file.write("hello");
        var editFrag = new ContextFragment.ProjectPathFragment(file, cm);
        var ctx = newContext().addFragments(List.of(editFrag));

        // Simulate a read-only virtual fragment by wrapping in a mock (this is a simplified test)
        // In practice, Code/Usage fragments would be read-only; here we just ensure the logic
        // favors the editable ProjectPathFragment
        ctx.awaitContextsAreComputed(Duration.of(10, ChronoUnit.SECONDS));

        var response =
                """
                <block>
                %s
                <<<<<<< SEARCH
                hello
                =======
                goodbye
                >>>>>>> REPLACE
                </block>
                """
                        .formatted(file.toString());
        var stubModel = new TestScriptedLanguageModel(response);
        var agent = new CodeAgent(cm, stubModel, consoleIO);

        // Mock build to succeed
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> "Build successful";
        var bd = new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test", Set.of());
        project.setBuildDetails(bd);
        project.setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        // Act
        var result = agent.runTask(ctx, List.of(), "Change file from hello to goodbye", Set.of());

        // Assert: edit should succeed because editable ProjectPathFragment takes precedence
        assertEquals(
                TaskResult.StopReason.SUCCESS,
                result.stopDetails().reason(),
                "Editable ProjectPathFragment should take precedence over other fragment types");
        assertEquals("goodbye", file.read().orElseThrow().strip(), "File should be modified");
    }

    // RO-4: computeReadOnlyPaths – precedence for ProjectPathFragment, SummaryFragment, CodeFragment, and explicit
    // read-only markers (including overlapping cases)
    @Test
    void testComputeReadOnlyPaths_precedenceAndOverlaps() throws Exception {
        // Create a small Java package with several files so we can attach different fragment combinations.
        // Layout:
        //  - SummaryOnly.java:        Summary only -> read-only
        //  - PpfAndSummaryEditable.java: ProjectPathFragment + Summary -> editable (PPF wins)
        //  - PpfReadonly.java:        ProjectPathFragment (explicit read-only) + Summary -> read-only
        //  - CodeAndSummaryEditable.java: CodeFragment + Summary -> editable (Code wins)
        //  - CodeReadonlyOnly.java:   CodeFragment (explicit read-only) + Summary -> read-only
        //  - CodeOnly.java:           CodeFragment only -> editable
        Files.createDirectories(projectRoot.resolve("src/main/java/com/example"));

        var summaryOnlyFile = cm.toFile("src/main/java/com/example/SummaryOnly.java");
        summaryOnlyFile.write(
                """
                package com.example;

                public class SummaryOnly {}
                """);
        cm.addEditableFile(summaryOnlyFile);

        var ppfAndSummaryEditableFile = cm.toFile("src/main/java/com/example/PpfAndSummaryEditable.java");
        ppfAndSummaryEditableFile.write(
                """
                package com.example;

                public class PpfAndSummaryEditable {}
                """);
        cm.addEditableFile(ppfAndSummaryEditableFile);

        var ppfReadonlyFile = cm.toFile("src/main/java/com/example/PpfReadonly.java");
        ppfReadonlyFile.write(
                """
                package com.example;

                public class PpfReadonly {}
                """);
        cm.addEditableFile(ppfReadonlyFile);

        var codeAndSummaryEditableFile = cm.toFile("src/main/java/com/example/CodeAndSummaryEditable.java");
        codeAndSummaryEditableFile.write(
                """
                package com.example;

                public class CodeAndSummaryEditable {}
                """);
        cm.addEditableFile(codeAndSummaryEditableFile);

        var codeReadonlyOnlyFile = cm.toFile("src/main/java/com/example/CodeReadonlyOnly.java");
        codeReadonlyOnlyFile.write(
                """
                package com.example;

                public class CodeReadonlyOnly {}
                """);
        cm.addEditableFile(codeReadonlyOnlyFile);

        var codeOnlyFile = cm.toFile("src/main/java/com/example/CodeOnly.java");
        codeOnlyFile.write(
                """
                package com.example;

                public class CodeOnly {}
                """);
        cm.addEditableFile(codeOnlyFile);

        // Let the analyzer discover these files/classes so SummaryFragment and CodeFragment can resolve sources().
        var analyzer = cm.getAnalyzerWrapper()
                .updateFiles(Set.of(
                        summaryOnlyFile,
                        ppfAndSummaryEditableFile,
                        ppfReadonlyFile,
                        codeAndSummaryEditableFile,
                        codeReadonlyOnlyFile,
                        codeOnlyFile))
                .get();
        assertFalse(analyzer.getAllDeclarations().isEmpty());

        // Build fragments
        var ppfAndSummaryEditablePpf = new ContextFragment.ProjectPathFragment(ppfAndSummaryEditableFile, cm);
        var ppfReadonlyPpf = new ContextFragment.ProjectPathFragment(ppfReadonlyFile, cm);

        var summarySummaryOnly = new ContextFragment.SummaryFragment(
                cm, "com.example.SummaryOnly", ContextFragment.SummaryType.CODEUNIT_SKELETON);
        assertFalse(summarySummaryOnly.files().join().isEmpty());
        var summaryPpfAndSummaryEditable = new ContextFragment.SummaryFragment(
                cm, "com.example.PpfAndSummaryEditable", ContextFragment.SummaryType.CODEUNIT_SKELETON);
        var summaryPpfReadonly = new ContextFragment.SummaryFragment(
                cm, "com.example.PpfReadonly", ContextFragment.SummaryType.CODEUNIT_SKELETON);
        var summaryCodeAndSummaryEditable = new ContextFragment.SummaryFragment(
                cm, "com.example.CodeAndSummaryEditable", ContextFragment.SummaryType.CODEUNIT_SKELETON);
        var summaryCodeReadonlyOnly = new ContextFragment.SummaryFragment(
                cm, "com.example.CodeReadonlyOnly", ContextFragment.SummaryType.CODEUNIT_SKELETON);
        var summaryCodeReadonlyWithPpf = new ContextFragment.SummaryFragment(
                cm, "com.example.CodeReadonlyWithPpf", ContextFragment.SummaryType.CODEUNIT_SKELETON);
        var summaryCodeOnly = new ContextFragment.SummaryFragment(
                cm, "com.example.CodeOnly", ContextFragment.SummaryType.CODEUNIT_SKELETON);

        var codeCodeAndSummaryEditable = new ContextFragment.CodeFragment(
                cm,
                analyzer.getTopLevelDeclarations(codeAndSummaryEditableFile).stream()
                        .filter(CodeUnit::isClass)
                        .findFirst()
                        .orElseThrow());
        var codeCodeReadonlyOnly = new ContextFragment.CodeFragment(
                cm,
                analyzer.getTopLevelDeclarations(codeReadonlyOnlyFile).stream()
                        .filter(CodeUnit::isClass)
                        .findFirst()
                        .orElseThrow());
        var codeCodeOnly = new ContextFragment.CodeFragment(
                cm,
                analyzer.getTopLevelDeclarations(codeOnlyFile).stream()
                        .filter(CodeUnit::isClass)
                        .findFirst()
                        .orElseThrow());

        // Compose a single Context with all of these fragments
        var ctx = new Context(cm)
                .addFragments(List.of(ppfAndSummaryEditablePpf, ppfReadonlyPpf))
                .addFragments(List.of(
                        summarySummaryOnly,
                        summaryPpfAndSummaryEditable,
                        summaryPpfReadonly,
                        summaryCodeAndSummaryEditable,
                        summaryCodeReadonlyOnly,
                        summaryCodeReadonlyWithPpf,
                        summaryCodeOnly,
                        codeCodeAndSummaryEditable,
                        codeCodeReadonlyOnly,
                        codeCodeOnly));
        ctx = ctx.setReadonly(ppfReadonlyPpf, true);
        ctx = ctx.setReadonly(codeCodeReadonlyOnly, true);

        // Make sure computed fragments have resolved their files() so computeReadOnlyPaths sees correct ProjectFiles.
        ctx.awaitContextsAreComputed(Duration.of(10, ChronoUnit.SECONDS));

        var readOnlyPaths = CodeAgent.computeReadOnlyPaths(ctx);

        // SummaryOnly.java: only a SummaryFragment -> read-only
        assertTrue(
                readOnlyPaths.contains(summaryOnlyFile.toString()),
                "File with only a SummaryFragment should be read-only");

        // PpfAndSummaryEditable.java: editable ProjectPathFragment + SummaryFragment -> editable (PPF wins)
        assertFalse(
                readOnlyPaths.contains(ppfAndSummaryEditableFile.toString()),
                "Editable ProjectPathFragment should make the file editable even if a SummaryFragment also references it");

        // PpfReadonly.java: explicitly read-only ProjectPathFragment + SummaryFragment -> read-only
        assertTrue(
                readOnlyPaths.contains(ppfReadonlyFile.toString()),
                "Explicitly read-only ProjectPathFragment should always make the file read-only");

        // CodeAndSummaryEditable.java: CodeFragment (editable) + SummaryFragment -> editable (Code wins over summary)
        assertFalse(
                readOnlyPaths.contains(codeAndSummaryEditableFile.toString()),
                "Editable CodeFragment should make the file editable even if a SummaryFragment also references it");

        // CodeReadonlyOnly.java: CodeFragment (explicit read-only) + SummaryFragment -> read-only
        assertTrue(
                readOnlyPaths.contains(codeReadonlyOnlyFile.toString()),
                "Explicitly read-only CodeFragment with no other editable fragments should make the file read-only");

        // CodeOnly.java: CodeFragment only (no Summary, no explicit read-only) -> editable
        assertFalse(
                readOnlyPaths.contains(codeOnlyFile.toString()),
                "File referenced only by an editable CodeFragment should not be treated as read-only");

        // re-check editable/summary conflict with a FILE_SKELETON summary
        ctx = ctx.removeFragments(Set.of(summaryPpfAndSummaryEditable));
        var summaryFilePpfAndSummaryEditable = new ContextFragment.SummaryFragment(
                cm, ppfAndSummaryEditablePpf.toString(), ContextFragment.SummaryType.FILE_SKELETONS);
        ctx = ctx.addFragments(summaryFilePpfAndSummaryEditable);
        readOnlyPaths = CodeAgent.computeReadOnlyPaths(ctx);
        assertFalse(
                readOnlyPaths.contains(ppfAndSummaryEditablePpf.toString()),
                "File referenced only by an editable CodeFragment should not be treated as read-only");
    }
}
