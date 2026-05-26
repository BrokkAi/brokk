package ai.brokk.executor.jobs;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.brokk.AbstractService;
import ai.brokk.ContextManager;
import ai.brokk.Service;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestService;
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
                responseSchema);
    }

    private ChatRequest responseFormatRequest() {
        return model.requests.stream()
                .filter(request -> request.parameters().responseFormat() != null)
                .findFirst()
                .orElseThrow();
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
        private boolean throwOnResponseFormat;

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            requests.add(chatRequest);
            if (throwOnResponseFormat && chatRequest.parameters().responseFormat() != null) {
                throw new IllegalArgumentException("provider rejected response schema");
            }
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(new AiMessage("{\"summary\":\"ok\"}"))
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
