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
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class TestService extends AbstractService {

    private final Map<ModelType, StreamingChatModel> modelOverrides = new HashMap<>();

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

    @Override
    public boolean requiresEmulatedTools(StreamingChatModel model) {
        return false;
    }

    @Override
    public boolean supportsJsonSchema(StreamingChatModel model) {
        return true;
    }

    public void setModel(ModelType modelType, StreamingChatModel model) {
        modelOverrides.put(modelType, model);
    }

    @Override
    public @Nullable StreamingChatModel getModel(ModelType modelType) {
        if (modelOverrides.containsKey(modelType)) {
            return modelOverrides.get(modelType);
        }
        return super.getModel(modelType);
    }

    @Override
    public JsonNode reportClientException(String stacktrace, String clientVersion, Map<String, String> optionalFields)
            throws IOException {
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
