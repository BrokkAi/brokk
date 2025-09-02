package io.github.jbellis.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.LintResult;
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
            var cr = ChatResponse.builder()
                    .aiMessage(new AiMessage(responseText))
                    .build();
            handler.onCompleteResponse(cr);
        }
    }

    @TempDir
    Path projectRoot;

    TestContextManager contextManager;
    TestConsoleIO consoleIO;
    CodeAgent codeAgent;
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

        // Save original shell command runner factory
        originalShellCommandRunnerFactory = Environment.shellCommandRunnerFactory;
    }

    @AfterEach
    void tearDown() {
        // Restore original shell command runner factory
        Environment.shellCommandRunnerFactory = originalShellCommandRunnerFactory;
    }

    private CodeAgent.LoopContext createLoopContext(
            String goal,
            List<ChatMessage> taskMessages,
            UserMessage nextRequest,
            List<LineEdit> pendingEdits,
            int blocksAppliedWithoutBuild) {
        var conversationState = new CodeAgent.ConversationState(
                new ArrayList<>(taskMessages), // Modifiable copy
                nextRequest);
        var workspaceState = new CodeAgent.EditState(
                new ArrayList<>(pendingEdits), // Modifiable copy
                0, // consecutiveParseFailures
                0, // consecutiveApplyFailures
                0, // consecutiveBuildFailures
                blocksAppliedWithoutBuild,
                "", // lastBuildError
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

        var result = codeAgent.parsePhase(loopContext, proseOnlyText, false, null);

        // A prose-only response is not a parse error; it should result in a Continue step.
        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;
        assertEquals(0, continueStep.loopContext().editState().consecutiveParseFailures());
        assertTrue(continueStep.loopContext().editState().pendingEdits().isEmpty());
    }

    // P-2: parsePhase – partial parse + error
    @Test
    void testParsePhase_partialParseWithError() {
        var loopContext = createBasicLoopContext("test goal");
        // A valid edit block followed by trailing prose. The parser should extract
        // the block, and the prose will be ignored when materializing edits.
        String llmText =
                """
                         <brk_edit_file path="file.java" beginline=1 endline=1>
                         System.out.println("World");
                         </brk_edit_file>
                         This is some trailing text.
                         """;

        var result = codeAgent.parsePhase(loopContext, llmText, false, null);

        // The parser correctly handles mixed content; this is not a parse error.
        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;
        assertEquals(0, continueStep.loopContext().editState().consecutiveParseFailures());
        assertEquals(
                1,
                continueStep.loopContext().editState().pendingEdits().size(),
                "One block should be parsed and now pending.");
    }

    // P-3a: parsePhase – isPartial flag handling (with zero blocks)
    @Test
    void testParsePhase_isPartial_zeroBlocks() {
        var loopContext = createBasicLoopContext("test goal");
        String llmTextNoBlocks = "Thinking...";

        var result = codeAgent.parsePhase(loopContext, llmTextNoBlocks, true, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest())
                .contains("cut off before you provided any Line Edit tags"));
        assertTrue(retryStep.loopContext().editState().pendingEdits().isEmpty());
    }

    // P-3b: parsePhase – isPartial flag handling (with >=1 block)
    @Test
    void testParsePhase_isPartial_withBlocks() {
        var loopContext = createBasicLoopContext("test goal");
        String llmTextWithBlock =
                """
                                  <brk_edit_file path="file.java" beginline=1 endline=1>
                                  System.out.println("World");
                                  </brk_edit_file>
                                  """;

        var result = codeAgent.parsePhase(loopContext, llmTextWithBlock, true, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest())
                .contains("continue from there"));
        assertEquals(1, retryStep.loopContext().editState().pendingEdits().size());
    }

    // A-1: applyPhase – read-only conflict
    @Test
    void testApplyPhase_readOnlyConflict() {
        var readOnlyFile = contextManager.toFile("readonly.txt");
        contextManager.addReadonlyFile(readOnlyFile);

        var edit = new LineEdit.EditFile(readOnlyFile, 1, 1, "replace");
        var loopContext = createLoopContext("test goal", List.of(), new UserMessage("req"), List.of(edit), 0);

        var result = codeAgent.applyPhase(loopContext, null);

        assertInstanceOf(CodeAgent.Step.Fatal.class, result);
        var fatalStep = (CodeAgent.Step.Fatal) result;
        assertEquals(
                TaskResult.StopReason.READ_ONLY_EDIT, fatalStep.stopDetails().reason());
        assertTrue(fatalStep.stopDetails().explanation().contains(readOnlyFile.toString()));
    }

    // A-2: applyPhase – total apply failure (below fallback threshold)
    @Test
    void testApplyPhase_totalApplyFailure_belowThreshold() throws IOException {
        var file = contextManager.toFile("test.txt");
        file.write("initial content");
        contextManager.addEditableFile(file);

        // This should fail because the file only has one line
        var nonMatchingEdit = new LineEdit.EditFile(file, 10, 10, "replacement");
        var loopContext =
                createLoopContext("test goal", List.of(), new UserMessage("req"), List.of(nonMatchingEdit), 0);

        var result = codeAgent.applyPhase(loopContext, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertEquals(1, retryStep.loopContext().editState().consecutiveApplyFailures());
        assertEquals(0, retryStep.loopContext().editState().blocksAppliedWithoutBuild());
        String nextRequestText =
                Messages.getText(retryStep.loopContext().conversationState().nextRequest());
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

        // This should succeed, replacing line 1
        var successEdit = new LineEdit.EditFile(file1, 1, 1, "goodbye world");
        // This should fail, as file2 only has one line
        var failureEdit = new LineEdit.EditFile(file2, 10, 10, "text");

        var loopContext =
                createLoopContext("test goal", List.of(), new UserMessage("req"), List.of(successEdit, failureEdit), 0);
        var result = codeAgent.applyPhase(loopContext, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;

        // On partial success, consecutive failures should reset, and applied count should increment.
        assertEquals(
                0,
                retryStep.loopContext().editState().consecutiveApplyFailures(),
                "Consecutive failures should reset on partial success");
        assertEquals(
                1,
                retryStep.loopContext().editState().blocksAppliedWithoutBuild(),
                "One block should have been applied");

        // The retry message should reflect both the success and the failure.
        String nextRequestText =
                Messages.getText(retryStep.loopContext().conversationState().nextRequest());
        // Weaker assertion: just check that the name of the file that failed to apply is mentioned.
        assertTrue(nextRequestText.contains(file2.getFileName()));

        // Verify the successful edit was actually made.
        assertEquals("goodbye world", file1.read().strip());
    }

    // V-1: verifyPhase – skip when no edits
    @Test
    void testVerifyPhase_skipWhenNoEdits() {
        var loopContext = createLoopContext(
                "test goal", List.of(new AiMessage("no edits")), new UserMessage("test request"), List.of(), 0);
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

        var loopContext = createLoopContext("goal", List.of(), new UserMessage("req"), List.of(), 1); // 1 block applied

        // First run - build should fail
        var resultFail = codeAgent.verifyPhase(loopContext, null);
        assertInstanceOf(CodeAgent.Step.Retry.class, resultFail);
        var retryStep = (CodeAgent.Step.Retry) resultFail;
        assertTrue(retryStep.loopContext().editState().lastBuildError().contains("Detailed build error output"));
        assertEquals(0, retryStep.loopContext().editState().blocksAppliedWithoutBuild()); // Reset
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest())
                .contains("The build failed"));

        // Second run - build should succeed
        // We must manually create a new context that simulates new edits having been applied,
        // otherwise verifyPhase will short-circuit because blocksAppliedWithoutBuild is 0 from the Retry step.
        var contextForSecondRun = new CodeAgent.LoopContext(
                retryStep.loopContext().conversationState(),
                new CodeAgent.EditState(
                        List.of(), // pending blocks are empty
                        retryStep.loopContext().editState().consecutiveParseFailures(),
                        retryStep.loopContext().editState().consecutiveApplyFailures(),
                        retryStep.loopContext().editState().consecutiveBuildFailures(),
                        1, // Simulate one new fix was applied to pass the guard in verifyPhase
                        retryStep.loopContext().editState().lastBuildError(),
                        retryStep.loopContext().editState().changedFiles(),
                        retryStep.loopContext().editState().originalFileContents()),
                retryStep.loopContext().userGoal());

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

        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
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

        var firstResponse =
                """
                            <brk_edit_file path="test.txt" beginline=1 endline=1>
                            goodbye
                            </brk_edit_file>
                            """;
        var secondResponse = "I am unable to fix the build error.";
        var stubModel = new ScriptedLanguageModel(firstResponse, secondResponse);

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

        var edit = new LineEdit.EditFile(file, 1, 1, "new");
        var loopContext = createLoopContext("goal", List.of(), new UserMessage("req"), List.of(edit), 0);

        var result = codeAgent.applyPhase(loopContext, null);

        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;
        assertTrue(
                continueStep.loopContext().editState().changedFiles().contains(file),
                "changedFiles should include the edited file");
    }

    // L-3: Linting check - single lint error causes retry
    @Test
    void testVerifyPhase_lintErrorCausesRetry() throws IOException {
        var file = contextManager.toFile("Test.java");
        file.write("public class Test { }");
        contextManager.addEditableFile(file);

        // Configure mock analyzer to return lint errors
        var lintDiagnostic = new LintResult.LintDiagnostic(
                file.toString(), 1, 10, LintResult.LintDiagnostic.Severity.ERROR, "Missing semicolon", "CS001");
        contextManager.getMockAnalyzer().setLintBehavior(files -> new LintResult(List.of(lintDiagnostic)));

        // one block was applied to trigger verify phase
        var loopContext = createLoopContext("goal", List.of(), new UserMessage("req"), List.of(), 1);
        loopContext.editState().changedFiles().add(file);

        var result = codeAgent.verifyPhase(loopContext, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertEquals(1, retryStep.loopContext().editState().consecutiveBuildFailures());

        String retryMessage =
                Messages.getText(retryStep.loopContext().conversationState().nextRequest());
        assertTrue(retryMessage.contains("The build failed with the following error:"));
        assertTrue(retryMessage.contains("Missing semicolon"));
        assertTrue(retryMessage.contains(file.toString() + ":1:10"));
    }

    // L-4: Linting check - MAX_LINT_FAILURES reached
    @Test
    void testVerifyPhase_maxLintFailuresReached() throws IOException {
        var file = contextManager.toFile("Test.java");
        file.write("public class Test { }");
        contextManager.addEditableFile(file);

        // Configure mock analyzer to always return lint errors
        var lintDiagnostic = new LintResult.LintDiagnostic(
                file.toString(), 1, 10, LintResult.LintDiagnostic.Severity.ERROR, "Syntax error", "CS001");
        contextManager.getMockAnalyzer().setLintBehavior(files -> new LintResult(List.of(lintDiagnostic)));

        // Start with consecutive lint failures already at MAX_LINT_FAILURES - 1
        var loopContext = createLoopContext("goal", List.of(), new UserMessage("req"), List.of(), 1);
        var es = loopContext.editState();
        var esWithFailures = new CodeAgent.EditState(
                es.pendingEdits(),
                es.consecutiveParseFailures(),
                es.consecutiveApplyFailures(),
                CodeAgent.MAX_BUILD_FAILURES - 1, // Just below limit
                es.blocksAppliedWithoutBuild(),
                es.lastBuildError(),
                es.changedFiles(),
                es.originalFileContents());
        var lcWithFailures =
                new CodeAgent.LoopContext(loopContext.conversationState(), esWithFailures, loopContext.userGoal());
        lcWithFailures.editState().changedFiles().add(file);

        var result = codeAgent.verifyPhase(lcWithFailures, null);

        assertInstanceOf(CodeAgent.Step.Fatal.class, result);
        var fatalStep = (CodeAgent.Step.Fatal) result;
        assertEquals(TaskResult.StopReason.BUILD_ERROR, fatalStep.stopDetails().reason());
        assertTrue(fatalStep.stopDetails().explanation().contains("Build failed"));
    }
}
