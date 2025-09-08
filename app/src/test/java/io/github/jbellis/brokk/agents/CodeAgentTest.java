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
    EditBlockParser parser;
    BiFunction<String, Path, Environment.ShellCommandRunner> originalShellCommandRunnerFactory;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(projectRoot);
        consoleIO = new TestConsoleIO();
        contextManager = new TestContextManager(projectRoot, consoleIO);
        codeAgent = new CodeAgent(contextManager, new Service.UnavailableStreamingModel(), consoleIO);
        parser = EditBlockParser.instance;

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
            List<EditBlock.FileOperation> pendingBlocks,
            int blocksAppliedWithoutBuild) {
        var conversationState = new CodeAgent.ConversationState(
                new ArrayList<>(taskMessages),
                nextRequest,
                taskMessages.size());
        var workspaceState = new CodeAgent.EditState(
                new ArrayList<>(pendingBlocks),
                0,
                0,
                0,
                blocksAppliedWithoutBuild,
                "",
                new HashSet<>(),
                new HashMap<>()
        );
        return new CodeAgent.LoopContext(conversationState, workspaceState, goal);
    }

    private CodeAgent.LoopContext createBasicLoopContext(String goal) {
        return createLoopContext(goal, List.of(), new UserMessage("test request"), List.of(), 0);
    }

    @Test
    void testParsePhase_proseOnlyResponseIsNotError() {
        var loopContext = createBasicLoopContext("test goal");
        String proseOnlyText = "Okay, I will make the changes now.";

        var result = codeAgent.parsePhase(loopContext, proseOnlyText, false, parser, null);

        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;
        assertEquals(0, continueStep.loopContext().editState().consecutiveParseFailures());
        assertTrue(continueStep.loopContext().editState().pendingBlocks().isEmpty());
    }

    @Test
    void testParsePhase_partialParseWithError() {
        var loopContext = createBasicLoopContext("test goal");
        String llmText =
                """
                *** Begin Patch
                *** Add File: demo.txt
                +hello
                *** End Patch

                This is some trailing text.
                """;

        var result = codeAgent.parsePhase(loopContext, llmText, false, parser, null);

        assertInstanceOf(CodeAgent.Step.Continue.class, result);
        var continueStep = (CodeAgent.Step.Continue) result;
        assertEquals(0, continueStep.loopContext().editState().consecutiveParseFailures());
        assertEquals(1, continueStep.loopContext().editState().pendingBlocks().size());
    }

    @Test
    void testParsePhase_pureParseError_replacesLastRequest() {
        var originalRequest = new UserMessage("original user request");
        String llmTextWithParseError =
                """
                *** Begin Patch
                *** Update File: file.java
                *** End Patch
                """;
        var badAiResponse = new AiMessage(llmTextWithParseError);

        var taskMessages = new ArrayList<ChatMessage>();
        taskMessages.add(new UserMessage("some earlier message"));
        taskMessages.add(originalRequest);
        taskMessages.add(badAiResponse);

        var loopContext = createLoopContext("test goal", taskMessages, new UserMessage("placeholder"), List.of(), 0);

        var result = codeAgent.parsePhase(loopContext, llmTextWithParseError, false, parser, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        var newLoopContext = retryStep.loopContext();

        assertEquals(1, newLoopContext.editState().consecutiveParseFailures());

        var finalTaskMessages = newLoopContext.conversationState().taskMessages();
        assertEquals(1, finalTaskMessages.size());
        assertEquals("some earlier message", Messages.getText(finalTaskMessages.getFirst()));

        String nextRequestText =
                Messages.getText(newLoopContext.conversationState().nextRequest());
        assertTrue(nextRequestText.contains("original user request"));
        assertTrue(nextRequestText.contains("The edit format must be a single apply_patch envelope"));
    }

    @Test
    void testParsePhase_isPartial_zeroBlocks() {
        var loopContext = createBasicLoopContext("test goal");
        String llmTextNoBlocks = "Thinking...";

        var result = codeAgent.parsePhase(loopContext, llmTextNoBlocks, true, parser, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest())
                           .contains("Please continue the apply_patch envelope"));
        assertTrue(retryStep.loopContext().editState().pendingBlocks().isEmpty());
    }

    @Test
    void testParsePhase_isPartial_withBlocks() {
        var loopContext = createBasicLoopContext("test goal");
        String llmTextWithBlock =
                """
                *** Begin Patch
                *** Add File: demo.txt
                +hello
                *** End Patch
                """;

        var result = codeAgent.parsePhase(loopContext, llmTextWithBlock, true, parser, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertTrue(Messages.getText(retryStep.loopContext().conversationState().nextRequest())
                           .contains("continue from there"));
        assertEquals(1, retryStep.loopContext().editState().pendingBlocks().size());
    }

    @Test
    void testApplyPhase_totalApplyFailure_belowThreshold() throws IOException {
        var file = contextManager.toFile("test.txt");
        file.write("initial content\n");
        contextManager.addEditableFile(file);

        var nonMatchingBlock = new EditBlock.UpdateFile(
                file.toString(),
                null,
                List.of(new EditBlock.UpdateFileChunk(
                        null,
                        List.of(),
                        List.of("text that does not exist"),
                        List.of("replacement"),
                        List.of(),
                        false)));

        var loopContext =
                createLoopContext("test goal", List.of(), new UserMessage("req"), List.of(nonMatchingBlock), 0);

        var result = codeAgent.applyPhase(loopContext, parser, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;
        assertEquals(1, retryStep.loopContext().editState().consecutiveApplyFailures());
        assertEquals(0, retryStep.loopContext().editState().blocksAppliedWithoutBuild());
        String nextRequestText =
                Messages.getText(retryStep.loopContext().conversationState().nextRequest());
        assertTrue(nextRequestText.contains(file.getFileName()));
    }

    @Test
    void testApplyPhase_mixSuccessAndFailure() throws IOException {
        var file1 = contextManager.toFile("file1.txt");
        file1.write("hello world\n");
        contextManager.addEditableFile(file1);

        var file2 = contextManager.toFile("file2.txt");
        file2.write("foo bar\n");
        contextManager.addEditableFile(file2);

        var successBlock = new EditBlock.UpdateFile(
                file1.toString(),
                null,
                List.of(new EditBlock.UpdateFileChunk(
                        null,
                        List.of(),
                        List.of("hello world"),
                        List.of("goodbye world"),
                        List.of(),
                        false)));
        var failureBlock = new EditBlock.UpdateFile(
                file2.toString(),
                null,
                List.of(new EditBlock.UpdateFileChunk(
                        null,
                        List.of(),
                        List.of("nonexistent"),
                        List.of("text"),
                        List.of(),
                        false)));

        var loopContext = createLoopContext(
                "test goal", List.of(), new UserMessage("req"), List.of(successBlock, failureBlock), 0);
        var result = codeAgent.applyPhase(loopContext, parser, null);

        assertInstanceOf(CodeAgent.Step.Retry.class, result);
        var retryStep = (CodeAgent.Step.Retry) result;

        assertEquals(0, retryStep.loopContext().editState().consecutiveApplyFailures());
        assertEquals(1, retryStep.loopContext().editState().blocksAppliedWithoutBuild());

        String nextRequestText =
                Messages.getText(retryStep.loopContext().conversationState().nextRequest());
        assertTrue(nextRequestText.contains(file2.getFileName()));

        assertEquals("goodbye world\n", file1.read());
    }

    @Test
    void testVerifyPhase_skipWhenNoEdits() {
        var loopContext = createLoopContext(
                "test goal", List.of(new AiMessage("no edits")), new UserMessage("test request"), List.of(), 0);
        var result = codeAgent.verifyPhase(loopContext, null);

        assertInstanceOf(CodeAgent.Step.Fatal.class, result);
        var step = (CodeAgent.Step.Fatal) result;
        assertEquals(TaskResult.StopReason.SUCCESS, step.stopDetails().reason());
    }

    @Test
    void testVerifyPhase_verificationCommandAbsent() {
        contextManager.getProject().setBuildDetails(BuildAgent.BuildDetails.EMPTY);
        var loopContext = createLoopContext("goal", List.of(), new UserMessage("req"), List.of(), 1);

        var result = codeAgent.verifyPhase(loopContext, null);

        assertInstanceOf(CodeAgent.Step.Fatal.class, result);
        var step = (CodeAgent.Step.Fatal) result;
        assertEquals(TaskResult.StopReason.SUCCESS, step.stopDetails().reason());
    }

    @Test
    void testVerifyPhase_buildFailureAndSuccessCycle() {
        var bd = new BuildAgent.BuildDetails("echo build", "echo testAll", "echo test {{files}}", Set.of());
        contextManager.getProject().setBuildDetails(bd);
        contextManager.getProject().setCodeAgentTestScope(IProject.CodeAgentTestScope.ALL);

        java.util.concurrent.atomic.AtomicInteger attempt = new java.util.concurrent.atomic.AtomicInteger(0);
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
                        retryStep.loopContext().editState().consecutiveParseFailures(),
                        retryStep.loopContext().editState().consecutiveApplyFailures(),
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

    @Test
    void testRunTask_exitsSuccessOnNoEdits() {
        var stubModel = new ScriptedLanguageModel("Okay, I see no changes are needed.");
        codeAgent = new CodeAgent(contextManager, stubModel, consoleIO);
        contextManager.getProject().setBuildDetails(BuildAgent.BuildDetails.EMPTY);

        var result = codeAgent.runTask("A request that results in no edits", false);

        assertEquals(TaskResult.StopReason.SUCCESS, result.stopDetails().reason());
        assertTrue(result.changedFiles().isEmpty());
    }

    @Test
    void testRunTask_exitsBuildErrorOnNoEditsWithPreviousError() throws IOException {
        var file = contextManager.toFile("test.txt");
        file.write("hello\n");
        contextManager.addEditableFile(file);

        var firstResponse =
                """
                *** Begin Patch
                *** Update File: test.txt
                @@
                -hello
                +goodbye
                *** End Patch
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
        assertEquals("goodbye\n", file.read());
    }

    @Test
    void testReplaceCurrentTurnMessages_replacesWholeTurn() {
        var msgs = new ArrayList<ChatMessage>();
        msgs.add(new UserMessage("old turn user"));
        msgs.add(new AiMessage("old turn ai"));

        int turnStart = msgs.size();
        msgs.add(new UserMessage("turn start"));
        msgs.add(new AiMessage("partial response 1"));
        msgs.add(new UserMessage("retry prompt"));
        msgs.add(new AiMessage("partial response 2"));

        var cs = new CodeAgent.ConversationState(msgs, new UserMessage("next request"), turnStart);
        var summary = "Here is the apply_patch envelope:\n\n<summary>";
        var replaced = cs.replaceCurrentTurnMessages(summary);

        var finalMsgs = replaced.taskMessages();
        assertEquals(4, finalMsgs.size());
        assertEquals("turn start", Messages.getText(finalMsgs.get(2)));
        assertEquals("Here is the apply_patch envelope:\n\n<summary>", ((AiMessage) finalMsgs.get(3)).text());
        assertEquals(finalMsgs.size(), replaced.turnStartIndex());
    }
}