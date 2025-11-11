package ai.brokk.testutil;

import ai.brokk.AbstractService;
import ai.brokk.IProject;
import ai.brokk.MainProject;
import ai.brokk.Service;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import java.io.File;
import java.io.IOException;
import org.jetbrains.annotations.Nullable;

public final class TestService extends AbstractService {

    private StreamingChatModel customQuickestModel;

    public TestService(IProject project) {
        super(project);
    }

    public void setQuickestModel(StreamingChatModel model) {
        this.customQuickestModel = model;
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

    @Override
    public StreamingChatModel getModel(
            AbstractService.ModelConfig config, @Nullable OpenAiChatRequestParameters.Builder parametersOverride) {
        return new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(new AiMessage("```\nnew content\n```"))
                        .build());
            }
        };
    }

    @Override
    public JsonNode reportClientException(String stacktrace, String clientVersion) throws IOException {
        return objectMapper.createObjectNode();
    }

    @Override
    public StreamingChatModel quickestModel() {
        if (customQuickestModel != null) {
            return customQuickestModel;
        }
        return super.quickestModel();
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
