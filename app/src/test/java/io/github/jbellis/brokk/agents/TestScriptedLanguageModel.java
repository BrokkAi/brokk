package io.github.jbellis.brokk.agents;

import static org.junit.jupiter.api.Assertions.fail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;

class TestScriptedLanguageModel implements StreamingChatModel {
    private final Queue<String> responses;

    TestScriptedLanguageModel(String... cannedTexts) {
        this.responses = new LinkedList<>(Arrays.asList(cannedTexts));
    }

    @Override
    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        String responseText = responses.poll();
        if (responseText == null) {
            fail("ScriptedLanguageModel ran out of responses.");
        }
        handler.onPartialResponse(responseText);
        var cr = ChatResponse.builder().aiMessage(new AiMessage(responseText)).build();
        handler.onCompleteResponse(cr);
    }
}
