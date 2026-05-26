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
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private ContextManager cm;
    private JobRunner runner;
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
        cm.createHeadless(true, new NoOpConsoleIO());
        runner = new JobRunner(cm, new JobStore(tempDir.resolve("jobstore")));
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
        var spec = spec(Map.of("mode", "ASK"), null, responseSchema());

        runner.runAsync("ask-schema", spec, new NoOpConsoleIO()).get(5, TimeUnit.SECONDS);

        var responseFormat = requireNonNull(responseFormatRequest().parameters().responseFormat());
        assertEquals("StrictReport", responseFormat.jsonSchema().name());
    }

    @Test
    void reportOnlyWithResponseSchemaAppliesResponseFormatToFinalRequest() throws Exception {
        var spec = spec(
                Map.of(), new JobSpec.ExecutionPolicy(JobSpec.ExecutionPolicyPreset.REPORT_ONLY), responseSchema());

        runner.runAsync("report-schema", spec, new NoOpConsoleIO()).get(5, TimeUnit.SECONDS);

        var responseFormat = requireNonNull(responseFormatRequest().parameters().responseFormat());
        assertEquals("StrictReport", responseFormat.jsonSchema().name());
    }

    @Test
    void askWithoutResponseSchemaLeavesResponseFormatUnset() throws Exception {
        var spec = spec(Map.of("mode", "ASK"), null, null);

        runner.runAsync("ask-no-schema", spec, new NoOpConsoleIO()).get(5, TimeUnit.SECONDS);

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
        var spec = spec(Map.of("mode", "ASK"), null, responseSchema());

        var thrown = assertThrows(
                ExecutionException.class, () -> runner.runAsync("ask-schema-unsupported", spec, new NoOpConsoleIO())
                        .get(5, TimeUnit.SECONDS));

        var cause = requireNonNull(thrown.getCause());
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertEquals("MODEL_UNSUPPORTED_RESPONSE_SCHEMA: stub-model", cause.getMessage());
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

    private static JobSpec.ResponseSchema responseSchema() {
        try {
            return new JobSpec.ResponseSchema(
                    "StrictReport",
                    MAPPER.readTree(
                            """
                            {
                              "type": "object",
                              "properties": {
                                "summary": { "type": "string" }
                              },
                              "required": ["summary"],
                              "additionalProperties": false
                            }
                            """));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
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

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            requests.add(chatRequest);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(new AiMessage("{\"summary\":\"ok\"}"))
                    .build());
        }
    }
}
