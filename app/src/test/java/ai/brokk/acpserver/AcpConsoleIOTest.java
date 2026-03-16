package ai.brokk.acpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.LlmOutputMeta;
import dev.langchain4j.data.message.ChatMessageType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AcpConsoleIOTest {
    @Test
    void routesReasoningAndNotificationsToSeparateSinks() {
        var chunks = new ArrayList<String>();
        var notifications = new ArrayList<String>();
        var errors = new ArrayList<String>();
        var io = new AcpConsoleIO(
                (text, reasoning) -> chunks.add((reasoning ? "thought:" : "msg:") + text),
                notifications::add,
                errors::add);

        io.llmOutput("hello", ChatMessageType.AI, LlmOutputMeta.DEFAULT);
        io.llmOutput("thinking", ChatMessageType.AI, LlmOutputMeta.reasoning());
        io.showNotification(ai.brokk.IConsoleIO.NotificationRole.INFO, "done");
        io.toolError("bad", "Error");

        assertEquals(List.of("msg:hello", "thought:thinking"), chunks);
        assertEquals(List.of("[INFO] done"), notifications);
        assertEquals(List.of("[Error] bad"), errors);
    }
}
