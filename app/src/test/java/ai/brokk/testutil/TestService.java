package ai.brokk.testutil;

import ai.brokk.AbstractService;
import ai.brokk.Service;
import ai.brokk.project.IProject;
import ai.brokk.project.MainProject;
import ai.brokk.project.ModelProperties.ModelType;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public class TestService extends AbstractService {

    private final Map<ModelType, StreamingChatModel> modelOverrides = new HashMap<>();
    private boolean supportsJsonSchema = true;

    public TestService(IProject project) {
        super(project);
    }

    @Override
    public String nameOf(StreamingChatModel model) {
        return "stub-model";
    }

    @Override
    public boolean isLazy(StreamingChatModel model) {
        return false;
    }

    @Override
    public boolean isReasoning(StreamingChatModel model) {
        return false;
    }

    public void setSupportsJsonSchema(boolean supportsJsonSchema) {
        this.supportsJsonSchema = supportsJsonSchema;
    }

    @Override
    public boolean supportsJsonSchema(StreamingChatModel model) {
        return supportsJsonSchema;
    }

    public void setModel(ModelType modelType, StreamingChatModel model) {
        modelOverrides.put(modelType, model);
    }

    public void setAvailableModels(Map<String, Map<String, Object>> modelInfoByName) {
        this.modelInfoMap = modelInfoByName.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
        this.modelLocations = modelInfoByName.keySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(name -> name, name -> name));
    }

    public static Map<String, Object> modelInfo(boolean supportsReasoningEffort, boolean supportsReasoningDisable) {
        var supportedParams =
                supportsReasoningEffort ? List.of("reasoning_effort", "temperature") : List.of("temperature");
        return Map.ofEntries(
                Map.entry("supported_openai_params", supportedParams),
                Map.entry("supports_reasoning_disable", supportsReasoningDisable),
                Map.entry("model_location", "unused"),
                Map.entry("mode", "chat"),
                Map.entry("max_input_tokens", 8192),
                Map.entry("max_output_tokens", 2048),
                Map.entry("supports_tool_choice", true),
                Map.entry("supports_response_schema", false),
                Map.entry("supports_vision", false),
                Map.entry("supports_reasoning", supportsReasoningEffort),
                Map.entry("is_codex", false),
                Map.entry("free_tier_eligible", true),
                Map.entry("is_private", true),
                Map.entry("input_cost_per_token", 0.0),
                Map.entry("output_cost_per_token", 0.0),
                Map.entry("cache_read_input_token_cost", 0.0));
    }

    @Override
    public @Nullable StreamingChatModel getModel(ModelType modelType) {
        if (modelOverrides.containsKey(modelType)) {
            return modelOverrides.get(modelType);
        }
        return super.getModel(modelType);
    }

    @Override
    public JsonNode reportClientException(JsonNode exceptionReport) throws IOException {
        return objectMapper.createObjectNode();
    }

    @Override
    public float getUserBalance() {
        return 0;
    }

    @Override
    public void sendFeedback(String category, String feedbackText, boolean includeDebugLog, File screenshotFile)
            throws IOException {
        // No-op for test service
    }

    // Backward-compatible provider entry point used by other tests
    public static Service.Provider provider(MainProject project) {
        return new Service.Provider() {
            private TestService svc = new TestService(project);

            @Override
            public AbstractService get() {
                return svc;
            }

            @Override
            public void reinit(IProject p) {
                svc = new TestService(p);
            }
        };
    }
}
