package ai.brokk.agents;

import ai.brokk.context.Context;
import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record SearchState(Context context, List<ChatMessage> sessionMessages, @Nullable Context lastTurnContext) {
    public static SearchState initial(Context context) {
        return new SearchState(context, List.of(), null);
    }

    public SearchState withContext(Context context) {
        return new SearchState(context, sessionMessages, lastTurnContext);
    }

    public SearchState withSessionMessages(List<ChatMessage> sessionMessages) {
        return new SearchState(context, sessionMessages, lastTurnContext);
    }

    public SearchState withLastTurnContext(@Nullable Context lastTurnContext) {
        return new SearchState(context, sessionMessages, lastTurnContext);
    }
}
