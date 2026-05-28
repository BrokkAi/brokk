package ai.brokk.executor.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AbstractService;
import ai.brokk.ContextManager;
import ai.brokk.Service;
import ai.brokk.TaskResult;
import ai.brokk.executor.jobs.ResponseSchemaFixtures;
import ai.brokk.executor.jobs.ResponseSchemaRegistry;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.tools.ToolExecutionResult;
import ai.brokk.tools.ToolRegistry;
import ai.brokk.util.Json;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParallelCustomAgentTest {
    @TempDir
    Path tempDir;

    private static ToolExecutionRequest request() {
        return ToolExecutionRequest.builder()
                .id("call-1")
                .name("callCustomAgent")
                .arguments("{}")
                .build();
    }

    private static ToolExecutionRequest schemaNameRequest(String id, String agentName) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name("callCustomAgentWithSchema")
                .arguments(Json.toJson(Map.of(
                        "agentName", agentName,
                        "task", "Return a strict report.",
                        "responseSchemaName", "StrictReport")))
                .build();
    }

    private static ToolExecutionRequest blankSchemaNameRequest(String id, String agentName) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name("callCustomAgentWithSchema")
                .arguments(Json.toJson(Map.of(
                        "agentName", agentName,
                        "task", "Return a strict report.",
                        "responseSchemaName", " ")))
                .build();
    }

    private static ResponseSchemaRegistry schemaRegistry() {
        return ResponseSchemaRegistry.of(List.of(ResponseSchemaFixtures.validResponseSchema()));
    }

    @Test
    void toToolExecutionResult_throwsOnInterruptedStopReason() {
        var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED, "Cancelled by user.");

        assertThrows(
                InterruptedException.class,
                () -> ParallelCustomAgent.toToolExecutionResult(request(), stopDetails, stopDetails.explanation()));
    }

    @Test
    void toToolExecutionResult_returnsFatalForLlmError() throws InterruptedException {
        var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, "LLM failed");

        var result = ParallelCustomAgent.toToolExecutionResult(request(), stopDetails, "LLM failed");

        assertEquals(ToolExecutionResult.Status.FATAL, result.status());
        assertEquals("LLM failed", result.resultText());
    }

    @Test
    void toToolExecutionResult_preservesNonFatalStopReasonsAsSuccess() throws InterruptedException {
        var stopDetails = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ABORTED, "Need more context");

        var result = ParallelCustomAgent.toToolExecutionResult(request(), stopDetails, "Need more context");

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("Need more context", result.resultText());
    }

    @Test
    void toSchemaToolExecutionResult_returnsFatalForAnyNonSuccessStopReason() throws InterruptedException {
        var aborted = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ABORTED, "Child aborted");
        var toolError = new TaskResult.StopDetails(TaskResult.StopReason.TOOL_ERROR, "No terminal tool");

        var abortedResult = ParallelCustomAgent.toSchemaToolExecutionResult(request(), aborted, "Child aborted");
        var toolErrorResult = ParallelCustomAgent.toSchemaToolExecutionResult(request(), toolError, "No terminal tool");

        assertEquals(ToolExecutionResult.Status.FATAL, abortedResult.status());
        assertEquals("Child aborted", abortedResult.resultText());
        assertEquals(ToolExecutionResult.Status.FATAL, toolErrorResult.status());
        assertEquals("No terminal tool", toolErrorResult.resultText());
    }

    @Test
    void callCustomAgentWithSchemaArgumentsExposeSchemaNameString() throws Exception {
        try (var harness = Harness.create(tempDir, true)) {
            var tr = ToolRegistry.empty()
                    .builder()
                    .register(new ParallelCustomAgent(harness.cm(), harness.model()))
                    .build();

            var invocation = tr.validateTool(schemaNameRequest("call-1", "schema-agent"));
            var toolSpec = tr.getRegisteredTool("callCustomAgentWithSchema").orElseThrow();

            assertEquals("schema-agent", invocation.parameters().getFirst());
            assertEquals("Return a strict report.", invocation.parameters().get(1));
            assertEquals("StrictReport", invocation.parameters().get(2));
            assertTrue(toolSpec.parameters().properties().get("responseSchemaName") instanceof JsonStringSchema);
        }
    }

    @Test
    void sequentialSchemaBackedAgentResolvesSchemaNameFromParentRegistry() throws Exception {
        try (var harness = Harness.create(tempDir, true)) {
            harness.cm().getAgentStore().save(agentDef(), "project");
            var tools = new CustomAgentTools(harness.cm(), harness.model(), schemaRegistry());

            var result = tools.callCustomAgentWithSchema("schema-agent", "Return a strict report.", "StrictReport");

            assertEquals("{\"summary\":\"structured\"}", result);
        }
    }

    @Test
    void parallelSchemaBackedAgentsReturnOneStructuredResultPerChild() throws Exception {
        try (var harness = Harness.create(tempDir, true)) {
            harness.cm().getAgentStore().save(agentDef(), "project");
            var parallelCustomAgent = new ParallelCustomAgent(harness.cm(), harness.model(), schemaRegistry());
            var tr = harness.cm()
                    .getToolRegistry()
                    .builder()
                    .register(parallelCustomAgent)
                    .build();

            var result = parallelCustomAgent.execute(
                    List.of(schemaNameRequest("call-1", "schema-agent"), schemaNameRequest("call-2", "schema-agent")),
                    tr);

            assertEquals(TaskResult.StopReason.SUCCESS, result.stopDetails().reason());
            assertEquals(2, result.toolExecutionMessages().size());
            assertEquals(
                    List.of("{\"summary\":\"structured\"}", "{\"summary\":\"structured\"}"),
                    result.toolExecutionMessages().stream()
                            .map(message -> message.text())
                            .toList());
        }
    }

    @Test
    void parallelSchemaBackedAgentsCollectSiblingResultsAfterFatalChild() throws Exception {
        try (var harness = Harness.create(tempDir, true)) {
            var badAgentPrompt = "You are the bad schema test agent.";
            var goodAgentPrompt = "You are the good schema test agent.";
            harness.cm().getAgentStore().save(agentDef("bad-schema-agent", badAgentPrompt), "project");
            harness.cm().getAgentStore().save(agentDef("good-schema-agent", goodAgentPrompt), "project");
            harness.model().structuredResponsesBySystemPrompt.put(badAgentPrompt, "{\"summary\":null}");
            harness.model().structuredResponsesBySystemPrompt.put(goodAgentPrompt, "{\"summary\":\"structured\"}");
            var parallelCustomAgent = new ParallelCustomAgent(harness.cm(), harness.model(), schemaRegistry());
            var tr = harness.cm()
                    .getToolRegistry()
                    .builder()
                    .register(parallelCustomAgent)
                    .build();

            var result = parallelCustomAgent.execute(
                    List.of(
                            schemaNameRequest("call-1", "bad-schema-agent"),
                            schemaNameRequest("call-2", "good-schema-agent")),
                    tr);

            assertEquals(TaskResult.StopReason.LLM_ERROR, result.stopDetails().reason());
            assertEquals(2, result.toolExecutionMessages().size());
            assertTrue(result.toolExecutionMessages().getFirst().text().contains("RESPONSE_SCHEMA_OUTPUT_INVALID"));
            assertEquals(
                    "{\"summary\":\"structured\"}",
                    result.toolExecutionMessages().get(1).text());
        }
    }

    @Test
    void parallelSchemaBackedAgentResolvesSchemaNameFromParentRegistry() throws Exception {
        try (var harness = Harness.create(tempDir, true)) {
            harness.cm().getAgentStore().save(agentDef(), "project");
            var parallelCustomAgent = new ParallelCustomAgent(harness.cm(), harness.model(), schemaRegistry());
            var tr = harness.cm()
                    .getToolRegistry()
                    .builder()
                    .register(parallelCustomAgent)
                    .build();

            var result = parallelCustomAgent.execute(List.of(schemaNameRequest("call-1", "schema-agent")), tr);

            assertEquals(TaskResult.StopReason.SUCCESS, result.stopDetails().reason());
            assertEquals(
                    List.of("{\"summary\":\"structured\"}"),
                    result.toolExecutionMessages().stream()
                            .map(message -> message.text())
                            .toList());
        }
    }

    @Test
    void parallelSchemaBackedAgentRejectsBlankSchemaNameAsRequestError() throws Exception {
        try (var harness = Harness.create(tempDir, true)) {
            harness.cm().getAgentStore().save(agentDef(), "project");
            var parallelCustomAgent = new ParallelCustomAgent(harness.cm(), harness.model(), schemaRegistry());
            var tr = harness.cm()
                    .getToolRegistry()
                    .builder()
                    .register(parallelCustomAgent)
                    .build();

            var result = parallelCustomAgent.execute(List.of(blankSchemaNameRequest("call-1", "schema-agent")), tr);

            assertEquals(TaskResult.StopReason.SUCCESS, result.stopDetails().reason());
            assertTrue(result.toolExecutionMessages().getFirst().text().contains("responseSchemaName is required"));
        }
    }

    @Test
    void parallelSchemaBackedAgentRejectsUnresolvedSchemaReferenceAsRequestError() throws Exception {
        try (var harness = Harness.create(tempDir, true)) {
            harness.cm().getAgentStore().save(agentDef(), "project");
            var parallelCustomAgent = new ParallelCustomAgent(harness.cm(), harness.model());
            var tr = harness.cm()
                    .getToolRegistry()
                    .builder()
                    .register(parallelCustomAgent)
                    .build();

            var result = parallelCustomAgent.execute(List.of(schemaNameRequest("call-1", "schema-agent")), tr);

            assertEquals(TaskResult.StopReason.SUCCESS, result.stopDetails().reason());
            assertEquals(1, result.toolExecutionMessages().size());
            assertTrue(
                    result.toolExecutionMessages()
                            .getFirst()
                            .text()
                            .contains(
                                    "responseSchemaName 'StrictReport' was not found in the parent task schemas. Available schemas: []"));
        }
    }

    @Test
    void parallelSchemaBackedAgentDoesNotScanPromptTextForSchemas() throws Exception {
        try (var harness = Harness.create(tempDir, true)) {
            harness.cm().getAgentStore().save(agentDef(), "project");
            var promptWithSchema =
                    "Schema in text only:\n" + Json.toJson(ResponseSchemaFixtures.validResponseSchemaMap());
            var parallelCustomAgent = new ParallelCustomAgent(harness.cm(), harness.model());
            var tr = harness.cm()
                    .getToolRegistry()
                    .builder()
                    .register(parallelCustomAgent)
                    .build();

            assertTrue(promptWithSchema.contains("StrictReport"));
            var result = parallelCustomAgent.execute(List.of(schemaNameRequest("call-1", "schema-agent")), tr);

            assertEquals(TaskResult.StopReason.SUCCESS, result.stopDetails().reason());
            assertTrue(result.toolExecutionMessages().getFirst().text().contains("Available schemas: []"));
        }
    }

    @Test
    void parallelSchemaBackedAgentUnsupportedModelIsHardFailure() throws Exception {
        try (var harness = Harness.create(tempDir, false)) {
            harness.cm().getAgentStore().save(agentDef(), "project");
            var parallelCustomAgent = new ParallelCustomAgent(harness.cm(), harness.model(), schemaRegistry());
            var tr = harness.cm()
                    .getToolRegistry()
                    .builder()
                    .register(parallelCustomAgent)
                    .build();

            var result = parallelCustomAgent.execute(List.of(schemaNameRequest("call-1", "schema-agent")), tr);

            assertEquals(TaskResult.StopReason.LLM_ERROR, result.stopDetails().reason());
            assertTrue(result.stopDetails().explanation().contains("MODEL_UNSUPPORTED_RESPONSE_SCHEMA: stub-model"));
            assertEquals(1, result.toolExecutionMessages().size());
            assertTrue(result.toolExecutionMessages()
                    .getFirst()
                    .text()
                    .contains("MODEL_UNSUPPORTED_RESPONSE_SCHEMA: stub-model"));
        }
    }

    private static AgentDefinition agentDef() {
        return agentDef("schema-agent", "You are a custom test agent.");
    }

    private static AgentDefinition agentDef(String name, String systemPrompt) {
        return new AgentDefinition(name, "desc", List.of("answer"), 1, systemPrompt, "project");
    }

    private record Harness(ContextManager cm, CapturingModel model) implements AutoCloseable {
        static Harness create(Path tempDir, boolean supportsJsonSchema) throws Exception {
            var workspaceDir = tempDir.resolve("workspace");
            Files.createDirectories(workspaceDir.resolve(".brokk/llm-history"));
            Files.writeString(workspaceDir.resolve(".brokk/project.properties"), "# test", StandardCharsets.UTF_8);

            var project = new MainProject(workspaceDir);
            var model = new CapturingModel();
            var service = new CapturingService(project, model, supportsJsonSchema);
            Service.Provider provider = new Service.Provider() {
                @Override
                public AbstractService get() {
                    return service;
                }

                @Override
                public void reinit(IProject project) {
                    // Keep the capturing service stable for assertions.
                }
            };
            var cm = new ContextManager(project, provider);
            cm.createHeadless(true, new NoOpConsoleIO());
            return new Harness(cm, model);
        }

        @Override
        public void close() {
            cm.close();
        }
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
        private final Map<String, String> structuredResponsesBySystemPrompt = new HashMap<>();

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            if (chatRequest.parameters().responseFormat() != null) {
                if (chatRequest.messages().getFirst() instanceof SystemMessage systemMessage) {
                    var configuredResponse = structuredResponsesBySystemPrompt.get(systemMessage.text());
                    if (configuredResponse != null) {
                        handler.onCompleteResponse(ChatResponse.builder()
                                .aiMessage(new AiMessage(configuredResponse))
                                .build());
                        return;
                    }
                }
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(new AiMessage("{\"summary\":\"structured\"}"))
                        .build());
                return;
            }
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                            .id("answer-1")
                            .name("answer")
                            .arguments(Json.toJson(Map.of("explanation", "plain notes")))
                            .build()))
                    .build());
        }
    }
}
