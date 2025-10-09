package io.github.jbellis.brokk.testutil;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import java.io.IOException;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class TestService extends Service {

    private StreamingChatModel customQuickestModel;

    public TestService(IProject project) {
        super(project);
    }

    public void setQuickestModel(StreamingChatModel model) {
        this.customQuickestModel = model;
    }

    @Override
    protected void fetchAvailableModels(
            MainProject.DataRetentionPolicy policy,
            Map<String, String> locationsTarget,
            Map<String, Map<String, Object>> infoTarget)
            throws IOException {}

    @Override
    public String nameOf(@Nullable StreamingChatModel model) {
        return "stub-model";
    }

    @Override
    public boolean isLazy(@Nullable StreamingChatModel model) {
        return false;
    }

    @Override
    public boolean isReasoning(@Nullable StreamingChatModel model) {
        return false;
    }

    @Override
    public boolean requiresEmulatedTools(@Nullable StreamingChatModel model) {
        return false;
    }

    @Override
    public boolean supportsJsonSchema(@Nullable StreamingChatModel model) {
        return true;
    }

    @Override
    public StreamingChatModel getModel(
            ModelConfig config, @Nullable OpenAiChatRequestParameters.Builder parametersOverride) {
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
    public StreamingChatModel quickestModel() {
        if (customQuickestModel != null) {
            return customQuickestModel;
        }
        return super.quickestModel();
    }
}
