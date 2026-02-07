package ai.brokk.cli;

import ai.brokk.LlmOutputMeta;
import dev.langchain4j.data.message.ChatMessageType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class CliConsole extends HeadlessConsole {
    private static final Logger logger = LogManager.getLogger(CliConsole.class);

    @Override
    protected void printLlmOutput(String token, ChatMessageType type, LlmOutputMeta meta) {
        logger.trace("LLM Output [{}]: {}", type, token);
    }
}
