package io.github.jbellis.brokk.testutil;

import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.IConsoleIO;

public class NoOpConsoleIO implements IConsoleIO {
    @Override
    public void toolError(String msg, String title) {
        
    }

    @Override
    public void llmOutput(String token, ChatMessageType type, boolean isNewMessage) {

    }
}
