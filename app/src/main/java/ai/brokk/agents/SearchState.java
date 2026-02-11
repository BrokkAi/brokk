package ai.brokk.agents;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import dev.langchain4j.data.message.ChatMessage;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public record SearchState(
        Context context,
        List<ChatMessage> sessionMessages,
        @Nullable Context lastTurnContext,
        Set<ProjectFile> presentedRelatedFiles) {
    public static SearchState initial(Context context) {
        return new SearchState(context, List.of(), null, Collections.emptySet());
    }

    public SearchState withContext(Context context) {
        return new SearchState(context, sessionMessages, lastTurnContext, presentedRelatedFiles);
    }

    public SearchState withSessionMessages(List<ChatMessage> sessionMessages) {
        return new SearchState(context, sessionMessages, lastTurnContext, presentedRelatedFiles);
    }

    public SearchState withLastTurnContext(@Nullable Context lastTurnContext) {
        return new SearchState(context, sessionMessages, lastTurnContext, presentedRelatedFiles);
    }

    public SearchState withPresentedRelatedFiles(Set<ProjectFile> presentedRelatedFiles) {
        return new SearchState(
                context,
                sessionMessages,
                lastTurnContext,
                Collections.unmodifiableSet(new HashSet<>(presentedRelatedFiles)));
    }
}
