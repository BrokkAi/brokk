package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessageType;

class TestConsoleIO implements IConsoleIO {
    private final StringBuilder outputLog = new StringBuilder();
    private final StringBuilder errorLog = new StringBuilder();

    @Override
    public void actionOutput(String text) {
        outputLog.append(text).append("\n");
    }

    @Override
    public void toolError(String msg, String title) {
        errorLog.append(msg).append("\n");
    }

    @Override
    public void llmOutput(String token, ChatMessageType type, boolean isNewMessage) {
        // not needed for these tests
    }

    public String getOutputLog() {
        return outputLog.toString();
    }

    public String getErrorLog() {
        return errorLog.toString();
    }
}
