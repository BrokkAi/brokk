package io.github.jbellis.brokk.agents;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.testutil.TestConsoleIO;
import io.github.jbellis.brokk.testutil.TestContextManager;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class CodeAgentTest {

    private static class ScriptedLanguageModel implements StreamingChatModel {
        private final Queue<String> responses;

        ScriptedLanguageModel(String... cannedTexts) {
            this.responses = new LinkedList<>(Arrays.asList(cannedTexts));
        }

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            String responseText = responses.poll();
            if (responseText == null) {
                fail("ScriptedLanguageModel ran out of responses.");
            }
            handler.onPartialResponse(responseText);
	    var cr = ChatResponse.builder().aiMessage(new AiMessage(responseText)).build();
            handler.onCompleteResponse(cr);
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
        parser = EditBlockParser.getParserFor(""); // Basic parser

        // Save original shell command runner factory
        originalShellCommandRunnerFactory = Environment.shellCommandRunnerFactory;
    }

    @AfterEach
    void tearDown() {
        // Restore original shell command runner factory
        Environment.shellCommandRunnerFactory = originalShellCommandRunnerFactory;
    }

    private CodeAgent.LoopContext createLoopContext(String goal,
                                                    List<ChatMessage> taskMessages,
                                                    UserMessage nextRequest,
                                                    List<EditBlock.SearchReplaceBlock> pendingBlocks,
                                                    int blocksAppliedWithoutBuild)
    {
        var conversationState = new CodeAgent.ConversationState(
                new ArrayList<>(taskMessages), // Modifiable copy
                nextRequest
        );
        var workspaceState = new CodeAgent.EditState(
                new ArrayList<>(pendingBlocks), // Modifiable copy
                0,
                0,
                blocksAppliedWithoutBuild,
                "",
                new HashSet<>(), // changedFiles
                new HashMap<>() // originalFileContents
        );
        return new CodeAgent.LoopContext(conversationState, workspaceState, goal);
    }

    private CodeAgent.LoopContext createBasicLoopContext(String goal) {
        return createLoopContext(goal, List.of(), new UserMessage("test request"), List.of(), 0);
    }

    // P-1: parsePhase – pure parse error (prose-only response)
    @Test
    void testParsePhase_proseOnlyResponseIsNotError() {
        var loopContext = createBasicLoopContext("test goal");
        // This input contains no blocks and should be treated as a successful, empty parse.
        String proseOnlyText = "Okay, I will make the changes now.";

        var result = codeAgent.parsePhase(loopContext, proseOnlyText, false, parser, null);

        // A prose-only response is not a parse error; it should result in a Continue step.
        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;
        assertEquals(0, continueStep.loopContext().editState().consecutiveParseFailures());
        assertTrue(continueStep.loopContext().editState().pendingBlocks().isEmpty());
    }

    // P-2: parsePhase – partial parse + error
    @Test
    void testParsePhase_partialParseWithError() {
        var loopContext = createBasicLoopContext("test goal");
        // A valid block followed by malformed text. The lenient parser should find
        // the first block and then stop without reporting an error.
        String llmText = """
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

        var result = codeAgent.parsePhase(loopContext, llmText, false, parser, null);

        // The parser is lenient; it finds the valid block and ignores the rest.
        // This is not a parse error, so we continue.
        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;
        assertEquals(0, continueStep.loopContext().editState().consecutiveParseFailures());
        assertEquals(1, continueStep.loopContext().editState().pendingBlocks().size(), "One block should be parsed and now pending.");
    }

    // P-3a: parsePhase – isPartial flag handling (with zero blocks)
    @Test
    void testParsePhase_isPartial_zeroBlocks() {
        var loopContext = createBasicLoopContext("test goal");
        String llmTextNoBlocks = "Thinking...";

        var result = codeAgent.parsePhase(loopContext, llmTextNoBlocks, true, parser, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest()).contains("cut off before you provided any code blocks"));
        assertTrue(retryStep.loopContext().editState().pendingBlocks().isEmpty());
    }

    // P-3b: parsePhase – isPartial flag handling (with >=1 block)
    @Test
    void testParsePhase_isPartial_withBlocks() {
        var loopContext = createBasicLoopContext("test goal");
        String llmTextWithBlock = """
                                  <block>
                                  file.java
                                  <<<<<<< SEARCH
                                  System.out.println("Hello");
                                  =======
                                  System.out.println("World");
                                  >>>>>>> REPLACE
                                  </block>
                                  """;

        var result = codeAgent.parsePhase(loopContext, llmTextWithBlock, true, parser, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest()).contains("continue from there"));
        assertEquals(1, retryStep.loopContext().editState().pendingBlocks().size());
    }

    // A-1: applyPhase – read-only conflict
    @Test
    void testApplyPhase_readOnlyConflict() {
        var readOnlyFile = contextManager.toFile("readonly.txt");
        contextManager.addReadonlyFile(readOnlyFile);

        var block = new EditBlock.SearchReplaceBlock(readOnlyFile.toString(), "search", "replace");
        var loopContext = createLoopContext("test goal", List.of(), new UserMessage("req"), List.of(block), 0);

        var result = codeAgent.applyPhase(loopContext, parser, null);

        assertInstanceOf(CodeAgent.Step.Fatal.class, result);
        var fatalStep = (CodeAgent.Step.Fatal) result;
        assertEquals(TaskResult.StopReason.READ_ONLY_EDIT, fatalStep.stopDetails().reason());
        assertTrue(fatalStep.stopDetails().explanation().contains(readOnlyFile.toString()));
    }

    // A-2: applyPhase – total apply failure (below fallback threshold)
    @Test
    void testApplyPhase_totalApplyFailure_belowThreshold() throws IOException {
        var file = contextManager.toFile("test.txt");
        file.write("initial content");
        contextManager.addEditableFile(file);

        var nonMatchingBlock = new EditBlock.SearchReplaceBlock(file.toString(), "text that does not exist", "replacement");
        var loopContext = createLoopContext("test goal", List.of(), new UserMessage("req"), List.of(nonMatchingBlock), 0);

        var result = codeAgent.applyPhase(loopContext, parser, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertEquals(1, retryStep.loopContext().editState().consecutiveApplyFailures());
        assertEquals(0, retryStep.loopContext().editState().blocksAppliedWithoutBuild());
        String nextRequestText = Messages.getText(retryStep.loopContext().conversationState().nextRequest());
        // check that the name of the file that failed to apply is mentioned in the retry prompt.
        assertTrue(nextRequestText.contains(file.getFileName()));
    }

    // A-3: applyPhase - triggering full-file fallback
    @Test
    void testApplyPhase_triggersFullFileFallback() throws IOException {
        var file = contextManager.toFile("test.txt");
        file.write("initial content");
        contextManager.addEditableFile(file);

        var nonMatchingBlock = new EditBlock.SearchReplaceBlock(file.toString(), "nonexistent", "text");

        // Fail just under the threshold
        var currentContext = createLoopContext("goal", List.of(), new UserMessage("req"), List.of(nonMatchingBlock), 0);
        for (int i = 0; i < CodeAgent.MAX_APPLY_FAILURES_BEFORE_FALLBACK - 1; i++) {
            // Re-seed the pending blocks for this attempt
            var contextForThisAttempt = new CodeAgent.LoopContext(
                currentContext.conversationState(),
                new CodeAgent.EditState(List.of(nonMatchingBlock), // re-add the block
                                        currentContext.editState().consecutiveParseFailures(),
                                        currentContext.editState().consecutiveApplyFailures(),
                                        currentContext.editState().blocksAppliedWithoutBuild(),
                                        currentContext.editState().lastBuildError(),
                                        currentContext.editState().changedFiles(),
                                        currentContext.editState().originalFileContents()),
                currentContext.userGoal());

            var result = codeAgent.applyPhase(contextForThisAttempt, parser, null);
            assertInstanceOf(CodeAgent.Step.Retry.class, result, "Should be a retry on failure " + (i + 1));
            currentContext = result.loopContext();
            assertEquals(i + 1, currentContext.editState().consecutiveApplyFailures());
        }

        // Final failure that should trigger fallback
        // We need to inject the failing block again for the final attempt
        var finalContext = new CodeAgent.LoopContext(
            currentContext.conversationState(),
            new CodeAgent.EditState(List.of(nonMatchingBlock),
                                    currentContext.editState().consecutiveParseFailures(),
                                    currentContext.editState().consecutiveApplyFailures(),
                                    currentContext.editState().blocksAppliedWithoutBuild(),
                                    currentContext.editState().lastBuildError(),
                                    currentContext.editState().changedFiles(),
                                    currentContext.editState().originalFileContents()),
            currentContext.userGoal()
        );

        var result = codeAgent.applyPhase(finalContext, parser, null);

        assertInstanceOf(CodeAgent.Step.Continue.class, result, "Should continue after successful fallback");
        var continueStep = (CodeAgent.Step.Continue) result;
        assertEquals(0, continueStep.loopContext().editState().consecutiveApplyFailures(), "Failures should reset after fallback");
        assertEquals(1, continueStep.loopContext().editState().blocksAppliedWithoutBuild(), "Should force build after fallback");
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

        var loopContext = createLoopContext("test goal", List.of(), new UserMessage("req"), List.of(successBlock, failureBlock), 0);
        var result = codeAgent.applyPhase(loopContext, parser, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;

        // On partial success, consecutive failures should reset, and applied count should increment.
        assertEquals(0, retryStep.loopContext().editState().consecutiveApplyFailures(), "Consecutive failures should reset on partial success");
        assertEquals(1, retryStep.loopContext().editState().blocksAppliedWithoutBuild(), "One block should have been applied");
        
        // The retry message should reflect both the success and the failure.
        String nextRequestText = Messages.getText(retryStep.loopContext().conversationState().nextRequest());
        // Weaker assertion: just check that the name of the file that failed to apply is mentioned.
        assertTrue(nextRequestText.contains(file2.getFileName()));

        // Verify the successful edit was actually made.
        assertEquals("goodbye world", file1.read().strip());
    }

    // V-1: verifyPhase – skip when no edits
    @Test
    void testVerifyPhase_skipWhenNoEdits() {
        var loopContext = createLoopContext("test goal",
                                            List.of(new AiMessage("no edits")),
                                            new UserMessage("test request"),
                                            List.of(),
                                            0);
        var result = codeAgent.verifyPhase(loopContext, null);

        assertInstanceOf(CodeAgent.Step.Fatal.class, result);
        var step = (CodeAgent.Step.Fatal) result;
        assertEquals(TaskResult.StopReason.SUCCESS, step.stopDetails().reason());
    }

    // V-2: verifyPhase – verification command absent
    @Test
    void testVerifyPhase_verificationCommandAbsent() {
        contextManager.getProject().setBuildDetails(BuildAgent.BuildDetails.EMPTY); // No commands
        var loopContext = createLoopContext("goal", List.of(), new UserMessage("req"), List.of(), 1); // 1 block applied

        var result = codeAgent.verifyPhase(loopContext, null);

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

        java.util.concurrent.atomic.AtomicInteger attempt = new java.util.concurrent.atomic.AtomicInteger(0);
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer) -> {
            int currentAttempt = attempt.getAndIncrement();
            // Log the attempt to help diagnose mock behavior using a more visible marker
            System.out.println("[TEST DEBUG] MockShellCommandRunner: Attempt " + currentAttempt + " for command: " + cmd);
            outputConsumer.accept("MockShell: attempt " + currentAttempt + " for command: " + cmd);
            if (currentAttempt == 0) { // First attempt fails
                outputConsumer.accept("Build error line 1");
                throw new Environment.FailureException("Build failed", "Detailed build error output");
            }
            // Second attempt (or subsequent if MAX_BUILD_FAILURES > 1) succeeds
            outputConsumer.accept("Build successful");
            return "Successful output";
        };

        var loopContext = createLoopContext("goal", List.of(), new UserMessage("req"), List.of(), 1); // 1 block applied

        // First run - build should fail
        var resultFail = codeAgent.verifyPhase(loopContext, null);
        assertInstanceOf(CodeAgent.Step.Retry.class, resultFail);
        var retryStep = (CodeAgent.Step.Retry) resultFail;
        assertTrue(retryStep.loopContext().editState().lastBuildError().contains("Detailed build error output"));
        assertEquals(0, retryStep.loopContext().editState().blocksAppliedWithoutBuild()); // Reset
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest()).contains("The build failed"));

        // Second run - build should succeed
        // We must manually create a new context that simulates new edits having been applied,
        // otherwise verifyPhase will short-circuit because blocksAppliedWithoutBuild is 0 from the Retry step.
        var contextForSecondRun = new CodeAgent.LoopContext(
                retryStep.loopContext().conversationState(),
                new CodeAgent.EditState(
                        List.of(), // pending blocks are empty
                        retryStep.loopContext().editState().consecutiveParseFailures(),
                        retryStep.loopContext().editState().consecutiveApplyFailures(),
                        1, // Simulate one new fix was applied to pass the guard in verifyPhase
                        retryStep.loopContext().editState().lastBuildError(),
                        retryStep.loopContext().editState().changedFiles(),
                        retryStep.loopContext().editState().originalFileContents()
                ),
                retryStep.loopContext().userGoal()
        );

        var resultSuccess = codeAgent.verifyPhase(contextForSecondRun, null);
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

        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer) -> {
            throw new InterruptedException("Simulated interruption during shell command");
        };

        var loopContext = createLoopContext("goal", List.of(), new UserMessage("req"), List.of(), 1);

        var result = codeAgent.verifyPhase(loopContext, null);
        assertInstanceOf(CodeAgent.Step.Fatal.class, result);
        var fatalStep = (CodeAgent.Step.Fatal) result;
        assertEquals(TaskResult.StopReason.INTERRUPTED, fatalStep.stopDetails().reason());
    }

    // L-1: Loop termination - "no edits, no error"
    @Test
    void testRunTask_exitsSuccessOnNoEdits() {
        var stubModel = new ScriptedLanguageModel("Okay, I see no changes are needed.");
        codeAgent = new CodeAgent(contextManager, stubModel, consoleIO);
        contextManager.getProject().setBuildDetails(BuildAgent.BuildDetails.EMPTY); // No build command

        var result = codeAgent.runTask("A request that results in no edits", false);

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

        var firstResponse = """
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
        var stubModel = new ScriptedLanguageModel(firstResponse, secondResponse);

        // Make the build command fail once
        var buildAttempt = new AtomicInteger(0);
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer) -> {
            if (buildAttempt.getAndIncrement() == 0) {
                throw new Environment.FailureException("Build failed", "Compiler error on line 5");
            }
            return "Build successful";
        };

        var bd = new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test", Set.of());
        contextManager.getProject().setBuildDetails(bd);
        contextManager.getProject().setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        codeAgent = new CodeAgent(contextManager, stubModel, consoleIO);
        var result = codeAgent.runTask("change hello to goodbye", false);

        assertEquals(TaskResult.StopReason.BUILD_ERROR, result.stopDetails().reason());
        assertTrue(result.stopDetails().explanation().contains("Compiler error on line 5"));
        assertEquals("goodbye", file.read().strip()); // The edit was made and not reverted
    }

    // CF-1: changedFiles tracking after successful apply
    @Test
    void testApplyPhase_updatesChangedFilesSet() throws IOException {
        var file = contextManager.toFile("file.txt");
        file.write("old");
        contextManager.addEditableFile(file);

        var block = new EditBlock.SearchReplaceBlock(file.toString(), "old", "new");
        var loopContext = createLoopContext("goal", List.of(), new UserMessage("req"), List.of(block), 0);

        var result = codeAgent.applyPhase(loopContext, parser, null);

        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;
        assertTrue(continueStep.loopContext().editState().changedFiles().contains(file),
                   "changedFiles should include the edited file");
    }
}
