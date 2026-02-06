package ai.brokk.executor.jobs;

import ai.brokk.ContextManager;
import ai.brokk.agents.SearchAgent;
import ai.brokk.prompts.SearchPrompts;
import ai.brokk.util.Json;
import ai.brokk.util.TextUtil;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class IssueWriterService {
    private static final Logger logger = LogManager.getLogger(IssueWriterService.class);

    private final ContextManager cm;
    private final StreamingChatModel model;
    private final String userRequest;

    public IssueWriterService(ContextManager cm, StreamingChatModel model, String userRequest) {
        this.cm = cm;
        this.model = model;
        this.userRequest = userRequest;
    }

    static boolean shouldEnrichIssuePrompt(@Nullable String body) {
        return TextUtil.countWords(body) < JobRunner.ISSUE_PROMPT_ENRICHMENT_WORD_THRESHOLD;
    }

    public record IssueResponse(String title, String bodyMarkdown) {}

    public IssueResponse execute() throws InterruptedException {
        try (var scope = cm.beginTaskUngrouped("Issue Writer")) {
            var context = cm.liveContext();

            String goal =
                    """
                    Issue Writer: produce a high-quality GitHub issue by discovering and citing evidence in this repository.

                    User request:
                    %s
                    """
                            .formatted(userRequest);

            var agent = new SearchAgent(context, goal, model, SearchPrompts.Objective.ISSUE_DIAGNOSIS, scope);
            var result = agent.execute();
            scope.append(result);

            String raw = result.output().text().join();
            var parsed = parseIssueResponse(raw);

            String finalBodyMarkdown = maybeAnnotateDiffBlocks(parsed.bodyMarkdown());
            return new IssueResponse(parsed.title(), finalBodyMarkdown);
        }
    }

    private static final Pattern DIFF_FENCE_PATTERN = Pattern.compile("```diff\\R(.*?)\\R?```", Pattern.DOTALL);

    public static String maybeAnnotateDiffBlocks(String bodyMarkdown) {
        Matcher matcher = DIFF_FENCE_PATTERN.matcher(bodyMarkdown);
        if (!matcher.find()) {
            return bodyMarkdown;
        }

        matcher.reset();

        var out = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            out.append(bodyMarkdown, /* start= */ lastEnd, /* end= */ matcher.start());
            String content = matcher.group(1);
            String annotated = PrReviewService.annotateDiffWithLineNumbers(content);
            out.append("```diff\n").append(annotated).append("\n```");
            lastEnd = matcher.end();
        }
        out.append(bodyMarkdown.substring(lastEnd));
        return out.toString();
    }

    static IssueResponse parseIssueResponse(String rawText) {
        if (rawText.isBlank()) {
            throw new AssertionError(); // text is serialized by jackson in SearchAgent, should always be valid
        }

        logger.trace("parseIssueResponse: rawText length={}", rawText.length());

        JsonNode root;
        try {
            root = Json.getMapper().readTree(rawText);
        } catch (JacksonException initialParseError) {
            throw new AssertionError(); // text is serialized by jackson in SearchAgent, should always be valid
        }

        if (!root.has("title")
                || !root.get("title").isTextual()
                || root.get("title").asText().isBlank()) {
            throw new IllegalArgumentException("parseIssueResponse: missing or invalid 'title' field");
        }

        if (!root.has("bodyMarkdown")
                || !root.get("bodyMarkdown").isTextual()
                || root.get("bodyMarkdown").asText().isBlank()) {
            throw new IllegalArgumentException("parseIssueResponse: missing or invalid 'bodyMarkdown' field");
        }

        return new IssueResponse(
                root.get("title").asText(), root.get("bodyMarkdown").asText());
    }
}
