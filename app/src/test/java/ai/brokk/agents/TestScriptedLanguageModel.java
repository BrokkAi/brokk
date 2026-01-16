package ai.brokk.agents;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;

public class TestScriptedLanguageModel implements StreamingChatModel {
    private final Queue<String> responses;
    private final String fallbackResponse;

    public TestScriptedLanguageModel(String... cannedTexts) {
        this.responses = new LinkedList<>(Arrays.asList(cannedTexts));
        this.fallbackResponse =
                cannedTexts.length == 0 ? "TestScriptedLanguageModel: fallback response" : cannedTexts[0];
    }

    @Override
    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        String responseText = responses.poll();
        if (responseText == null) {
            responseText = fallbackResponse;
        }
        handler.onPartialResponse(responseText);
        var cr = ChatResponse.builder().aiMessage(new AiMessage(responseText)).build();
        handler.onCompleteResponse(cr);
    }
}
