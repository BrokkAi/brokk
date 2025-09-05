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
        codeAgent = new CodeAgent(contextManager, new Service.UnavailableStreamingModel(), consoleIO);
        originalShellCommandRunnerFactory = Environment.shellCommandRunnerFactory;
    }

    @AfterEach
    void tearDown() {
        Environment.shellCommandRunnerFactory = originalShellCommandRunnerFactory;
    }

    private CodeAgent.LoopContext createLoopContext(
            String goal,
            List<ChatMessage> taskMessages,
            UserMessage nextRequest,
            List<LineEdit> pendingEdits,
            int blocksAppliedWithoutBuild) {
        var conversationState = new CodeAgent.ConversationState(
                new ArrayList<>(taskMessages),
                nextRequest);
        var workspaceState = new CodeAgent.EditState(
                new ArrayList<>(pendingEdits),
                0, // consecutiveEditRetries
                0, // consecutiveNoResultRetries
                0, // consecutiveBuildFailures
                blocksAppliedWithoutBuild,
                "", // lastBuildError
                new HashSet<ProjectFile>(),
                new HashMap<ProjectFile, String>()
        );
        return new CodeAgent.LoopContext(conversationState, workspaceState, goal);
    }

    private CodeAgent.LoopContext createBasicLoopContext(String goal) {
        return createLoopContext(goal, List.of(), new UserMessage("test request"), List.of(), 0);
    }

    // E-1: editPhase – prose-only response (no blocks) is clean "no edits"
    @Test
    void testEditPhase_proseOnlyResponseIsNotError() {
        var loopContext = createBasicLoopContext("test goal");
        String proseOnlyText = "Okay, I will make the changes now.";

        var result = codeAgent.editPhase(loopContext, proseOnlyText, false, null);

        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;
        assertEquals(0, continueStep.loopContext().editState().consecutiveEditRetries());
        assertTrue(continueStep.loopContext().editState().pendingEdits().isEmpty());
    }

    // E-2: editPhase – valid block followed by trailing prose
    @Test
    void testEditPhase_validBlockPlusProse() throws IOException {
        var file = contextManager.toFile("file.java");
        file.write("old");
        contextManager.addEditableFile(file);

        var loopContext = createBasicLoopContext("test goal");
        String llmText =
                """
                BRK_EDIT_EX file.java
                1 c
                @1| old
                new
                .
                BRK_EDIT_EX_END
                This is some trailing text.
                """;

        var result = codeAgent.editPhase(loopContext, llmText, false, null);

        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        assertEquals("new", file.read().strip());
    }

    // E-3a: editPhase – isPartial flag handling (no blocks)
    @Test
    void testEditPhase_isPartial_zeroBlocks() {
        var loopContext = createBasicLoopContext("test goal");
        String llmTextNoBlocks = "Thinking...";

        var result = codeAgent.editPhase(loopContext, llmTextNoBlocks, true, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest())
                           .contains("Some of your Line Edit tags failed"));
        assertTrue(retryStep.loopContext().editState().pendingEdits().isEmpty());
    }

    // E-3b: editPhase – isPartial with >=1 block (should include last_good_edit)
    @Test
    void testEditPhase_isPartial_withBlocks() throws IOException {
        var file = contextManager.toFile("file.java");
        file.write("old");
        contextManager.addEditableFile(file);

        var loopContext = createBasicLoopContext("test goal");
        String llmTextWithBlock =
                """
                BRK_EDIT_EX file.java
                1 c
                @1| old
                new
                .
                BRK_EDIT_EX_END
                """;

        var result = codeAgent.editPhase(loopContext, llmTextWithBlock, true, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        String nextText = Messages.getText(retryStep.loopContext().conversationState().nextRequest());
        assertTrue(nextText.contains("<last_good_edit>"));
        assertTrue(retryStep.loopContext().editState().blocksAppliedWithoutBuild() >= 0);
    }

    // E-4: editPhase – read-only conflict fatal
    @Test
    void testEditPhase_readOnlyConflict() {
        var readOnlyFile = contextManager.toFile("readonly.txt");
        contextManager.addReadonlyFile(readOnlyFile);

        String llmText =
                """
                BRK_EDIT_EX readonly.txt
                1 c
                @1| foo
                bar
                .
                BRK_EDIT_EX_END
                """;

        var loopContext = createBasicLoopContext("test goal");
        var result = codeAgent.editPhase(loopContext, llmText, false, null);

        assertInstanceOf(CodeAgent.Step.Fatal.class, result);
        var fatalStep = (CodeAgent.Step.Fatal) result;
        assertEquals(TaskResult.StopReason.READ_ONLY_EDIT, fatalStep.stopDetails().reason());
        assertTrue(fatalStep.stopDetails().explanation().contains("readonly.txt"));
    }

    // E-5: editPhase – total apply failure (no success) should Retry and increment counters
    @Test
    void testEditPhase_totalApplyFailure_noSuccess() throws IOException {
        var file = contextManager.toFile("test.txt");
        file.write("initial content");
        contextManager.addEditableFile(file);

        String llmText =
                """
                BRK_EDIT_EX test.txt
                10 c
                @10| foo
                bar
                .
                BRK_EDIT_EX_END
                """;

        var loopContext = createBasicLoopContext("test goal");
        var result = codeAgent.editPhase(loopContext, llmText, false, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertEquals(1, retryStep.loopContext().editState().consecutiveEditRetries());
        assertEquals(1, retryStep.loopContext().editState().consecutiveNoResultRetries());
        String nextRequestText = Messages.getText(retryStep.loopContext().conversationState().nextRequest());
        assertTrue(nextRequestText.contains("test.txt"));
    }

    // E-6: editPhase – mixed success & failure (partial success resets no-result counter)
    @Test
    void testEditPhase_mixSuccessAndFailure() throws IOException {
        var file1 = contextManager.toFile("file1.txt");
        file1.write("hello world");
        contextManager.addEditableFile(file1);

        var file2 = contextManager.toFile("file2.txt");
        file2.write("foo bar");
        contextManager.addEditableFile(file2);

        String llmText =
                """
                BRK_EDIT_EX file1.txt
                1 c
                @1| hello world
                goodbye world
                .
                BRK_EDIT_EX_END

                BRK_EDIT_EX file2.txt
                10 c
                @10| nope
                text
                .
                BRK_EDIT_EX_END
                """;

        var loopContext = createBasicLoopContext("test goal");
        var result = codeAgent.editPhase(loopContext, llmText, false, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;

        // On partial success, no-result counter resets; blocksAppliedWithoutBuild increments by 1
        assertEquals(0, retryStep.loopContext().editState().consecutiveNoResultRetries());
        assertEquals(1, retryStep.loopContext().editState().blocksAppliedWithoutBuild());

        // Verify successful change
        assertEquals("goodbye world", file1.read().strip());
        // Retry text should mention the failed file (weaker assertion)
        String nextRequestText = Messages.getText(retryStep.loopContext().conversationState().nextRequest());
        assertTrue(nextRequestText.contains("file2.txt"));
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
        contextManager.getProject().setBuildDetails(BuildAgent.BuildDetails.EMPTY);
        var loopContext = createLoopContext("goal", List.of(), new UserMessage("req"), List.of(), 1);

        var result = codeAgent.verifyPhase(loopContext, null);

        assertInstanceOf(CodeAgent.Step.Fatal.class, result);
        var step = (CodeAgent.Step.Fatal) result;
        assertEquals(TaskResult.StopReason.SUCCESS, step.stopDetails().reason());
    }

    // V-3: verifyPhase – build failure then success
    @Test
    void testVerifyPhase_buildFailureAndSuccessCycle() {
        var bd = new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test {{files}}", Set.of());
        contextManager.getProject().setBuildDetails(bd);
        contextManager.getProject().setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        AtomicInteger attempt = new AtomicInteger(0);
        Environment.shellCommandRunnerFactory = (cmd, root) -> (outputConsumer, timeout) -> {
            int currentAttempt = attempt.getAndIncrement();
            System.out.println(
                    "[TEST DEBUG] MockShellCommandRunner: Attempt " + currentAttempt + " for command: " + cmd);
            outputConsumer.accept("MockShell: attempt " + currentAttempt + " for command: " + cmd);
            if (currentAttempt == 0) {
                outputConsumer.accept("Build error line 1");
                throw new Environment.FailureException("Build failed", "Detailed build error output");
            }
            outputConsumer.accept("Build successful");
            return "Successful output";
        };

        var loopContext = createLoopContext("goal", List.of(), new UserMessage("req"), List.of(), 1);

        var resultFail = codeAgent.verifyPhase(loopContext, null);
        assertInstanceOf(CodeAgent.Step.Retry.class, resultFail);
        var retryStep = (CodeAgent.Step.Retry) resultFail;
        assertTrue(retryStep.loopContext().editState().lastBuildError().contains("Detailed build error output"));
        assertEquals(0, retryStep.loopContext().editState().blocksAppliedWithoutBuild());
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest())
                           .contains("The build failed"));

        var contextForSecondRun = new CodeAgent.LoopContext(
                retryStep.loopContext().conversationState(),
                new CodeAgent.EditState(
                        List.of(),
                        retryStep.loopContext().editState().consecutiveEditRetries(),
                        retryStep.loopContext().editState().consecutiveNoResultRetries(),
                        retryStep.loopContext().editState().consecutiveBuildFailures(),
                        1,
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
        contextManager.getProject().setBuildDetails(BuildAgent.BuildDetails.EMPTY);

        var result = codeAgent.runTask("A request that results in no edits", false);

        assertEquals(TaskResult.StopReason.SUCCESS, result.stopDetails().reason());
        assertTrue(result.changedFiles().isEmpty());
    }

    // L-2: Loop termination - "no edits, but has build error"
    @Test
    void testRunTask_exitsBuildErrorOnNoEditsWithPreviousError() throws IOException {
        var file = contextManager.toFile("test.txt");
        file.write("hello");
        contextManager.addEditableFile(file);

        var firstResponse =
                """
                BRK_EDIT_EX test.txt
                1 c
                @1| hello
                goodbye
                .
                BRK_EDIT_EX_END
                """;
        var secondResponse = "I am unable to fix the build error.";
        var stubModel = new ScriptedLanguageModel(firstResponse, secondResponse);

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
        assertEquals("goodbye", file.read().strip());
    }

    // CF-1: changedFiles tracking after successful apply
    @Test
    void testEditPhase_updatesChangedFilesSet() throws IOException {
        var file = contextManager.toFile("file.txt");
        file.write("old");
        contextManager.addEditableFile(file);

        String llmText =
                """
                BRK_EDIT_EX file.txt
                1 c
                @1| old
                new
                .
                BRK_EDIT_EX_END
                """;
        var loopContext = createBasicLoopContext("goal");

        var result = codeAgent.editPhase(loopContext, llmText, false, null);

        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;
        assertTrue(
                continueStep.loopContext().editState().changedFiles().contains(file),
                "changedFiles should include the edited file");
    }
}
