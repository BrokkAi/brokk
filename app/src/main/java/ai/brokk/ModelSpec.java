package ai.brokk;

import static java.util.Objects.requireNonNull;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.jetbrains.annotations.Nullable;

public record ModelSpec(String name, @Nullable String reasoningLevel) {

    public static ModelSpec from(StreamingChatModel model, Service svc) {
        requireNonNull(model, "model");
        requireNonNull(svc, "svc");

        var canonicalName = svc.nameOf(model);

        String reasoning = null;
        if (model instanceof OpenAiStreamingChatModel om) {
            var params = om.defaultRequestParameters();
            var effort = params == null ? null : params.reasoningEffort();
            if (effort != null && !effort.isBlank()) {
                reasoning = effort;
            }
        }

        return new ModelSpec(canonicalName, reasoning);
    }
}
