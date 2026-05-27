package ai.brokk.executor.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AbstractService;
import ai.brokk.ContextManager;
import ai.brokk.LlmOutputMeta;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.executor.jobs.ResponseSchemaFixtures;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.NoOpConsoleIO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustomAgentExecutorTest {
    @TempDir
    Path tempDir;

    private @Nullable ContextManager cm;
    private @Nullable CapturingModel model;
    private @Nullable Service.Provider provider;

    @Test
    void formatTurnDirective_repeatsTaskOnlyOnFirstTurn() {
        var task = "Audit the payment workflow for race conditions.";
        var toc = "<workspace_toc>PaymentService.java</workspace_toc>";
        var previousAddition =
                """
                <fragment description="PaymentService">
                class PaymentService {}
                </fragment>
                """
                        .stripIndent();

        var firstTurn = CustomAgentExecutor.formatTurnDirective(1, 3, task, List.of(), toc);
        var secondTurn = CustomAgentExecutor.formatTurnDirective(2, 3, task, List.of(previousAddition), toc);

        assertTrue(firstTurn.contains("<task>" + task + "</task>"));
        assertFalse(secondTurn.contains(task));
        assertTrue(secondTurn.contains("<goal>Continue the custom agent task.</goal>"));
        assertTrue(secondTurn.contains("<previous_turn_additions>"));
        assertTrue(secondTurn.contains(previousAddition.trim()));
        assertTrue(
                secondTurn.contains("Call as many next tools in parallel as will most effectively advance your work."));
        assertTrue(secondTurn.contains(toc));
    }

    @Test
    void formatTurnDirective_preservesFinalTurnTerminalGuidance() {
        var directive = CustomAgentExecutor.formatTurnDirective(3, 3, "Do the thing.", List.of(), "<workspace_toc />");

        assertTrue(directive.contains("This is the final turn. Call 'answer' or 'abortSearch' to finish."));
    }

    @Test
    void schemaBackedExecutionAppliesResponseFormatToFinalRequest() throws Exception {
        setUpHarness(true);
        var io = new RecordingConsoleIO();
        var executor = new CustomAgentExecutor(cm, agentDef(), model, io, ResponseSchemaFixtures.validResponseSchema());

        var result = executor.executeInterruptibly("Return a strict report.");

        assertEquals(TaskResult.StopReason.SUCCESS, result.stopDetails().reason());
        assertEquals("{\"summary\":\"structured\"}", result.stopDetails().explanation());
        assertEquals(List.of("{\"summary\":\"structured\"}"), io.outputs);
        assertEquals(2, model.requests.size());
        assertNull(model.requests.getFirst().parameters().responseFormat());
        assertTrue(model.requests.get(1).parameters().responseFormat() != null);
        assertEquals(
                1,
                model.requests.stream()
                        .filter(request -> request.parameters().responseFormat() != null)
                        .count());
    }

    @Test
    void schemaBackedExecutionFailsBeforeToolLoopWhenModelUnsupported() throws Exception {
        setUpHarness(false);
        var executor = new CustomAgentExecutor(
                cm, agentDef(), model, new NoOpConsoleIO(), ResponseSchemaFixtures.validResponseSchema());

        var result = executor.executeInterruptibly("Return a strict report.");

        assertEquals(TaskResult.StopReason.LLM_ERROR, result.stopDetails().reason());
        assertEquals(
                "MODEL_UNSUPPORTED_RESPONSE_SCHEMA: stub-model",
                result.stopDetails().explanation());
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void noSchemaExecutionPreservesTerminalAnswerBehavior() throws Exception {
        setUpHarness(true);
        var io = new RecordingConsoleIO();
        var executor = new CustomAgentExecutor(cm, agentDef(), model, io);

        var result = executor.executeInterruptibly("Return Markdown.");

        assertEquals(TaskResult.StopReason.SUCCESS, result.stopDetails().reason());
        assertTrue(result.stopDetails().explanation().contains("plain notes"));
        assertEquals(List.of("# Answer\n\nplain notes"), io.outputs);
        assertEquals(
                0,
                model.requests.stream()
                        .filter(request -> request.parameters().responseFormat() != null)
                        .count());
    }

    @Test
    void schemaBackedFinalAnswerFailureReturnsHardErrorWithoutMarkdownFallback() throws Exception {
        setUpHarness(true);
        model.throwOnResponseFormat = true;
        var executor = new CustomAgentExecutor(
                cm, agentDef(), model, new NoOpConsoleIO(), ResponseSchemaFixtures.validResponseSchema());

        var result = executor.executeInterruptibly("Return a strict report.");

        assertEquals(TaskResult.StopReason.LLM_ERROR, result.stopDetails().reason());
        assertTrue(result.stopDetails().explanation().contains("provider rejected response schema"));
        assertEquals(2, model.requests.size());
        assertEquals(
                1,
                model.requests.stream()
                        .filter(request -> request.parameters().responseFormat() != null)
                        .count());
    }

    @Test
    void schemaBackedInvalidFinalAnswerRetriesWithSchemaFeedback() throws Exception {
        setUpHarness(true);
        model.structuredResponses.add("{\"summary\":null}");
        model.structuredResponses.add("{\"summary\":\"repaired\"}");
        var io = new RecordingConsoleIO();
        var executor = new CustomAgentExecutor(cm, agentDef(), model, io, ResponseSchemaFixtures.validResponseSchema());

        var result = executor.executeInterruptibly("Return a strict report.");

        assertEquals(TaskResult.StopReason.SUCCESS, result.stopDetails().reason());
        assertEquals("{\"summary\":\"repaired\"}", result.stopDetails().explanation());
        assertEquals(List.of("{\"summary\":\"repaired\"}"), io.outputs);
        assertEquals(3, model.requests.size());
        assertEquals(
                2,
                model.requests.stream()
                        .filter(request -> request.parameters().responseFormat() != null)
                        .count());
        var retryRequestText = ((UserMessage) model.requests.get(2).messages().get(1)).singleText();
        assertTrue(retryRequestText.contains("response.summary is required"));
        assertTrue(retryRequestText.contains("<invalid_previous_response>"));
    }

    @Test
    void schemaBackedInvalidFinalAnswerFailsAfterRepairAttemptWithoutMarkdownFallback() throws Exception {
        setUpHarness(true);
        model.structuredResponse = "{\"summary\":null}";
        var io = new RecordingConsoleIO();
        var executor = new CustomAgentExecutor(cm, agentDef(), model, io, ResponseSchemaFixtures.validResponseSchema());

        var result = executor.executeInterruptibly("Return a strict report.");

        assertEquals(TaskResult.StopReason.LLM_ERROR, result.stopDetails().reason());
        assertTrue(result.stopDetails().explanation().contains("RESPONSE_SCHEMA_OUTPUT_INVALID"));
        assertTrue(result.stopDetails().explanation().contains("response.summary is required"));
        assertTrue(io.outputs.isEmpty());
        assertEquals(3, model.requests.size());
        assertEquals(
                2,
                model.requests.stream()
                        .filter(request -> request.parameters().responseFormat() != null)
                        .count());
    }

    @AfterEach
    void tearDown() {
        if (cm != null) {
            cm.close();
        }
    }

    private void setUpHarness(boolean supportsJsonSchema) throws Exception {
        var workspaceDir = tempDir.resolve("workspace");
        Files.createDirectories(workspaceDir.resolve(".brokk/llm-history"));
        Files.writeString(workspaceDir.resolve(".brokk/project.properties"), "# test", StandardCharsets.UTF_8);

        var project = new MainProject(workspaceDir);
        model = new CapturingModel();
        var service = new CapturingService(project, model, supportsJsonSchema);
        provider = new Service.Provider() {
            @Override
            public AbstractService get() {
                return service;
            }

            @Override
            public void reinit(IProject project) {
                // Keep the capturing service stable for assertions.
            }
        };
        cm = new ContextManager(project, provider);
        cm.createHeadless(true, new NoOpConsoleIO());
    }

    private static AgentDefinition agentDef() {
        return new AgentDefinition(
                "schema-agent", "desc", List.of("answer"), 1, "You are a custom test agent.", "project");
    }

    private static final class CapturingService extends ai.brokk.testutil.TestService {
        private final StreamingChatModel model;
        private final boolean supportsJsonSchema;

        CapturingService(IProject project, StreamingChatModel model, boolean supportsJsonSchema) {
            super(project);
            this.model = model;
            this.supportsJsonSchema = supportsJsonSchema;
        }

        @Override
        public boolean supportsJsonSchema(StreamingChatModel model) {
            return supportsJsonSchema;
        }

        @Override
        public @Nullable StreamingChatModel getModel(
                ModelConfig config,
                @Nullable dev.langchain4j.model.openai.OpenAiChatRequestParameters.Builder parametersOverride) {
            return model;
        }
    }

    private static final class CapturingModel implements StreamingChatModel {
        private final CopyOnWriteArrayList<ChatRequest> requests = new CopyOnWriteArrayList<>();
        private final ArrayDeque<String> structuredResponses = new ArrayDeque<>();
        private boolean throwOnResponseFormat;
        private String structuredResponse = "{\"summary\":\"structured\"}";

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            requests.add(chatRequest);
            if (chatRequest.parameters().responseFormat() != null) {
                if (throwOnResponseFormat) {
                    throw new IllegalArgumentException("provider rejected response schema");
                }
                var responseText = structuredResponses.isEmpty() ? structuredResponse : structuredResponses.remove();
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(new AiMessage(responseText))
                        .build());
                return;
            }
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                            .id("answer-1")
                            .name("answer")
                            .arguments(ai.brokk.util.Json.toJson(Map.of("explanation", "plain notes")))
                            .build()))
                    .build());
        }
    }

    private static final class RecordingConsoleIO extends NoOpConsoleIO {
        private final List<String> outputs = new CopyOnWriteArrayList<>();

        @Override
        public void llmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
            outputs.add(token);
        }
    }
}
