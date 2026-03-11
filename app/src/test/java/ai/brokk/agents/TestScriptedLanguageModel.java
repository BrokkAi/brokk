package ai.brokk.agents;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import org.jetbrains.annotations.Nullable;

public class TestScriptedLanguageModel implements StreamingChatModel {
    private final Queue<AiMessage> responses;
    private final Queue<ChatResponse> cannedResponses;
    private final AiMessage fallbackResponse;
    private final List<ChatRequest> seenRequests = new ArrayList<>();

    public TestScriptedLanguageModel(String... cannedTexts) {
        this(Arrays.stream(cannedTexts).map(AiMessage::new).toList());
    }

    public TestScriptedLanguageModel(List<AiMessage> cannedResponses) {
        this.responses = new LinkedList<>(cannedResponses);
        this.cannedResponses = new LinkedList<>();
        this.fallbackResponse = cannedResponses.isEmpty()
                ? new AiMessage("TestScriptedLanguageModel: fallback response")
                : cannedResponses.getFirst();
    }

    public TestScriptedLanguageModel(ChatResponse... cannedResponses) {
        this.responses = new LinkedList<>();
        this.cannedResponses = new LinkedList<>(Arrays.asList(cannedResponses));
        this.fallbackResponse = cannedResponses.length == 0
                ? new AiMessage("TestScriptedLanguageModel: fallback response")
                : cannedResponses[0].aiMessage();
    }

    public List<ChatRequest> seenRequests() {
        return List.copyOf(seenRequests);
    }

    @Override
    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        seenRequests.add(chatRequest);

        ChatResponse prebuilt = cannedResponses.poll();
        if (prebuilt != null) {
            handler.onCompleteResponse(prebuilt);
            return;
        }

        @Nullable AiMessage ai = responses.poll();
        if (ai == null) {
            ai = fallbackResponse;
        }

        String text = ai.text() == null ? "" : ai.text();
        if (!text.isEmpty()) {
            handler.onPartialResponse(text);
        }
        var cr = ChatResponse.builder().aiMessage(ai).build();
        handler.onCompleteResponse(cr);
    }
}
