package ai.brokk.executor.jobs;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.AbstractService;
import ai.brokk.ContextManager;
import ai.brokk.Service;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestService;
import ai.brokk.util.Json;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobRunnerResponseSchemaTest {
    @TempDir
    Path tempDir;

    private ContextManager cm;
    private JobRunner runner;
    private JobStore store;
    private CapturingModel model;
    private CapturingService service;

    @BeforeEach
    void setUp() throws Exception {
        var workspaceDir = tempDir.resolve("workspace");
        Files.createDirectories(workspaceDir.resolve(".brokk/llm-history"));
        Files.writeString(workspaceDir.resolve(".brokk/project.properties"), "# test", StandardCharsets.UTF_8);

        var project = new MainProject(workspaceDir);
        model = new CapturingModel();
        service = new CapturingService(project, model);
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
        cm = new ContextManager(project, provider);
        cm.createHeadless(true, new RawMessagesConsole());
        store = new JobStore(tempDir.resolve("jobstore"));
        runner = new JobRunner(cm, store);
    }

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.shutdown();
        }
        if (cm != null) {
            cm.close();
        }
    }

    @Test
    void askWithResponseSchemaAppliesResponseFormatToFinalRequest() throws Exception {
        var spec = spec(Map.of("mode", "ASK"), null, ResponseSchemaFixtures.validResponseSchema());

        runner.runAsync("ask-schema", spec, new RawMessagesConsole()).get(5, TimeUnit.SECONDS);

        var responseFormat = requireNonNull(responseFormatRequest().parameters().responseFormat());
        assertEquals("StrictReport", responseFormat.jsonSchema().name());
    }

    @Test
    void reportOnlyWithResponseSchemaAppliesResponseFormatToFinalRequest() throws Exception {
        var spec = spec(
                Map.of(),
                new JobSpec.ExecutionPolicy(JobSpec.ExecutionPolicyPreset.REPORT_ONLY),
                ResponseSchemaFixtures.validResponseSchema());

        runner.runAsync("report-schema", spec, new RawMessagesConsole()).get(5, TimeUnit.SECONDS);

        var responseFormat = requireNonNull(responseFormatRequest().parameters().responseFormat());
        assertEquals("StrictReport", responseFormat.jsonSchema().name());
    }

    @Test
    void askWithoutResponseSchemaLeavesResponseFormatUnset() throws Exception {
        var spec = spec(Map.of("mode", "ASK"), null, null);

        runner.runAsync("ask-no-schema", spec, new RawMessagesConsole()).get(5, TimeUnit.SECONDS);

        assertNotNull(model.requests);
        assertEquals(
                0,
                model.requests.stream()
                        .filter(request -> request.parameters().responseFormat() != null)
                        .count());
    }

    @Test
    void responseSchemaFailsWhenModelDoesNotSupportJsonSchema() {
        service.supportsJsonSchema = false;
        var spec = spec(Map.of("mode", "ASK"), null, ResponseSchemaFixtures.validResponseSchema());

        var thrown = assertThrows(ExecutionException.class, () -> runner.runAsync(
                        "ask-schema-unsupported", spec, new RawMessagesConsole())
                .get(5, TimeUnit.SECONDS));

        var cause = requireNonNull(thrown.getCause());
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertEquals("MODEL_UNSUPPORTED_RESPONSE_SCHEMA: stub-model", cause.getMessage());
    }

    @Test
    void searchWithResponseSchemaPreflightsFinalModelBeforeScan() {
        service.supportsJsonSchema = false;
        var spec = spec(Map.of("mode", "SEARCH"), null, ResponseSchemaFixtures.validResponseSchema());

        var thrown = assertThrows(ExecutionException.class, () -> runner.runAsync(
                        "search-schema-unsupported", spec, new RawMessagesConsole())
                .get(5, TimeUnit.SECONDS));

        var cause = requireNonNull(thrown.getCause());
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertEquals("MODEL_UNSUPPORTED_RESPONSE_SCHEMA: stub-model", cause.getMessage());
        assertEquals(
                0,
                model.requests.stream()
                        .filter(request -> request.parameters().responseFormat() != null)
                        .count());
    }

    @Test
    void searchWithResponseSchemaUsesOneStructuredFinalCall() throws Exception {
        var spec = spec(Map.of("mode", "SEARCH"), null, ResponseSchemaFixtures.validResponseSchema());

        runner.runAsync("search-schema", spec, new RawMessagesConsole()).get(5, TimeUnit.SECONDS);

        assertEquals(
                1,
                model.requests.stream()
                        .filter(request -> request.parameters().responseFormat() != null)
                        .count());
        assertEquals(
                "StrictReport",
                requireNonNull(responseFormatRequest().parameters().responseFormat())
                        .jsonSchema()
                        .name());
    }

    @Test
    void schemaBackedDirectAnswerFailureFailsJob() throws Exception {
        model.throwOnResponseFormat = true;
        var spec = spec(Map.of("mode", "ASK"), null, ResponseSchemaFixtures.validResponseSchema());

        assertThrows(
                ExecutionException.class, () -> runner.runAsync("ask-schema-rejected", spec, new RawMessagesConsole())
                        .get(5, TimeUnit.SECONDS));

        assertEquals(
                JobStatus.State.FAILED.name(),
                requireNonNull(store.loadStatus("ask-schema-rejected")).state());
    }

    @Test
    void schemaBackedDirectAnswerInvalidOutputRetriesWithSchemaFeedback() throws Exception {
        model.structuredResponses.add("{\"summary\":null}");
        model.structuredResponses.add("{\"summary\":\"repaired\"}");
        var spec = spec(Map.of("mode", "ASK"), null, ResponseSchemaFixtures.validResponseSchema());

        runner.runAsync("ask-schema-invalid-output", spec, new RawMessagesConsole())
                .get(5, TimeUnit.SECONDS);

        assertEquals(
                JobStatus.State.COMPLETED.name(),
                requireNonNull(store.loadStatus("ask-schema-invalid-output")).state());
        assertEquals(
                2,
                model.requests.stream()
                        .filter(request -> request.parameters().responseFormat() != null)
                        .count());
        var responseFormatRequests = model.requests.stream()
                .filter(request -> request.parameters().responseFormat() != null)
                .toList();
        var retryRequestText = ((dev.langchain4j.data.message.UserMessage) responseFormatRequests
                        .get(1)
                        .messages()
                        .get(responseFormatRequests.get(1).messages().size() - 1))
                .singleText();
        assertTrue(retryRequestText.contains("response.summary"));
        assertTrue(retryRequestText.contains("<invalid_previous_response>"));
        assertTrue(retryRequestText.contains("<response_schema>"));
        assertTrue(retryRequestText.contains("Include every required top-level field"));
        assertTrue(retryRequestText.contains("If the schema requires `metadata`"));
    }

    @Test
    void schemaBackedDirectAnswerCoercesArrayValuedStringFieldWithoutRetry() throws Exception {
        model.structuredResponse = "{\"findings\":[\"one\",\"two\"],\"observations\":[\"kept\"]}";
        var spec = spec(Map.of("mode", "ASK"), null, narrativeSchema());

        runner.runAsync("ask-schema-coerced-string", spec, new RawMessagesConsole())
                .get(5, TimeUnit.SECONDS);

        assertEquals(
                JobStatus.State.COMPLETED.name(),
                requireNonNull(store.loadStatus("ask-schema-coerced-string")).state());
        assertEquals(
                1,
                model.requests.stream()
                        .filter(request -> request.parameters().responseFormat() != null)
                        .count());
    }

    @Test
    void schemaBackedDirectAnswerInvalidOutputFailsAfterRepairAttempt() throws Exception {
        model.structuredResponse = "{\"summary\":null}";
        var spec = spec(Map.of("mode", "ASK"), null, ResponseSchemaFixtures.validResponseSchema());

        var thrown = assertThrows(ExecutionException.class, () -> runner.runAsync(
                        "ask-schema-invalid-output-final", spec, new RawMessagesConsole())
                .get(5, TimeUnit.SECONDS));

        var cause = requireNonNull(thrown.getCause());
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertTrue(cause.getMessage().contains("RESPONSE_SCHEMA_OUTPUT_INVALID"), cause.getMessage());
        assertTrue(cause.getMessage().contains("schema=StrictReport"), cause.getMessage());
        assertTrue(cause.getMessage().contains("response.summary is required"), cause.getMessage());
        assertTrue(cause.getMessage().contains("attempts=2"), cause.getMessage());
        assertTrue(cause.getMessage().contains("finishReason=unknown"), cause.getMessage());
        assertTrue(cause.getMessage().contains("initialInvalidOutputExcerpt={\"summary\":null}"), cause.getMessage());
        assertTrue(cause.getMessage().contains("invalidOutputExcerpt={\"summary\":null}"), cause.getMessage());
        assertEquals(
                JobStatus.State.FAILED.name(),
                requireNonNull(store.loadStatus("ask-schema-invalid-output-final"))
                        .state());
        assertEquals(
                2,
                model.requests.stream()
                        .filter(request -> request.parameters().responseFormat() != null)
                        .count());
    }

    @Test
    void reportOnlySchemaFailureIncludesMetadataValidationAndMalformedOutputExcerpt() throws Exception {
        model.structuredResponse = "{\"summary\":\"wrong shape\"}";
        var spec = spec(
                Map.of(), new JobSpec.ExecutionPolicy(JobSpec.ExecutionPolicyPreset.REPORT_ONLY), synthesisSchema());

        var thrown = assertThrows(ExecutionException.class, () -> runner.runAsync(
                        "report-schema-invalid-output-final", spec, new RawMessagesConsole())
                .get(5, TimeUnit.SECONDS));

        var cause = rootCause(thrown);
        assertTrue(cause.getMessage().contains("RESPONSE_SCHEMA_OUTPUT_INVALID"), cause.getMessage());
        assertTrue(cause.getMessage().contains("schema=SlopCopFinalSynthesis"), cause.getMessage());
        assertTrue(cause.getMessage().contains("validation=response.metadata is required"), cause.getMessage());
        assertTrue(
                cause.getMessage().contains("invalidOutputExcerpt={\"summary\":\"wrong shape\"}"), cause.getMessage());
        assertEquals(
                JobStatus.State.FAILED.name(),
                requireNonNull(store.loadStatus("report-schema-invalid-output-final"))
                        .state());
    }

    @Test
    void schemaBackedDirectAnswerFailureTruncatesInvalidOutputExcerpt() throws Exception {
        var longValue = "x".repeat(7_000);
        model.structuredResponse = "{\"summary\":\"" + longValue + "\"}";
        var spec = spec(Map.of("mode", "ASK"), null, synthesisSchema());

        var thrown = assertThrows(ExecutionException.class, () -> runner.runAsync(
                        "ask-schema-invalid-output-truncated", spec, new RawMessagesConsole())
                .get(5, TimeUnit.SECONDS));

        var cause = rootCause(thrown);
        assertTrue(cause.getMessage().contains("RESPONSE_SCHEMA_OUTPUT_INVALID"), cause.getMessage());
        assertTrue(cause.getMessage().contains("[truncated invalid response: "), cause.getMessage());
        assertTrue(cause.getMessage().contains("chars omitted]"), cause.getMessage());
    }

    private static JobSpec spec(
            Map<String, String> tags,
            @Nullable JobSpec.ExecutionPolicy executionPolicy,
            @Nullable JobSpec.ResponseSchema responseSchema) {
        return new JobSpec(
                "write a strict response",
                false,
                false,
                "planner",
                null,
                null,
                false,
                tags,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                JobSpec.DEFAULT_MAX_ISSUE_FIX_ATTEMPTS,
                executionPolicy,
                responseSchema,
                List.of());
    }

    private ChatRequest responseFormatRequest() {
        return model.requests.stream()
                .filter(request -> request.parameters().responseFormat() != null)
                .findFirst()
                .orElseThrow();
    }

    private static Throwable rootCause(Throwable throwable) {
        var cause = requireNonNull(throwable.getCause());
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static JobSpec.ResponseSchema synthesisSchema() throws Exception {
        return new JobSpec.ResponseSchema(
                "SlopCopFinalSynthesis",
                Json.getMapper()
                        .readTree(
                                """
                                {
                                  "type": "object",
                                  "properties": {
                                    "metadata": {
                                      "type": "object",
                                      "properties": {
                                        "scan_id": { "type": "string" }
                                      },
                                      "required": ["scan_id"],
                                      "additionalProperties": false
                                    },
                                    "summary": { "type": "string" }
                                  },
                                  "required": ["metadata", "summary"],
                                  "additionalProperties": false
                                }
                                """));
    }

    private static JobSpec.ResponseSchema narrativeSchema() throws Exception {
        return new JobSpec.ResponseSchema(
                "SlopCopSynthesisNarrative",
                Json.getMapper()
                        .readTree(
                                """
                                {
                                  "type": "object",
                                  "properties": {
                                    "findings": { "type": "string" },
                                    "observations": {
                                      "type": "array",
                                      "items": { "type": "string" }
                                    }
                                  },
                                  "required": ["findings", "observations"],
                                  "additionalProperties": false
                                }
                                """));
    }

    private static final class CapturingService extends TestService {
        private final StreamingChatModel model;
        private boolean supportsJsonSchema = true;

        CapturingService(IProject project, StreamingChatModel model) {
            super(project);
            this.model = model;
        }

        @Override
        public boolean supportsJsonSchema(StreamingChatModel model) {
            return supportsJsonSchema;
        }

        @Override
        public @Nullable StreamingChatModel getModel(
                ModelConfig config, @Nullable OpenAiChatRequestParameters.Builder parametersOverride) {
            return model;
        }
    }

    private static final class CapturingModel implements StreamingChatModel {
        private final CopyOnWriteArrayList<ChatRequest> requests = new CopyOnWriteArrayList<>();
        private final ArrayDeque<String> structuredResponses = new ArrayDeque<>();
        private boolean throwOnResponseFormat;
        private String structuredResponse = "{\"summary\":\"ok\"}";

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            requests.add(chatRequest);
            if (throwOnResponseFormat && chatRequest.parameters().responseFormat() != null) {
                throw new IllegalArgumentException("provider rejected response schema");
            }
            var responseText = chatRequest.parameters().responseFormat() == null
                    ? structuredResponse
                    : structuredResponses.isEmpty() ? structuredResponse : structuredResponses.remove();
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(new AiMessage(responseText))
                    .build());
        }
    }

    private static final class RawMessagesConsole extends NoOpConsoleIO {
        @Override
        public List<ChatMessage> getLlmRawMessages() {
            return List.of();
        }
    }
}
