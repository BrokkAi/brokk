package ai.brokk.agents;

import ai.brokk.IContextManager;
import ai.brokk.context.Context;
import ai.brokk.executor.jobs.PrReviewService;
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

public final class IssueRewriterAgent {
    static final int ISSUE_PROMPT_ENRICHMENT_WORD_THRESHOLD = 100;
    private static final Logger logger = LogManager.getLogger(IssueRewriterAgent.class);

    private final IContextManager cm;
    private final StreamingChatModel model;
    private final String userRequest;
    private final Context context;

    public IssueRewriterAgent(Context context, StreamingChatModel model, String userRequest) {
        this.cm = context.getContextManager();
        this.context = context;
        this.model = model;
        this.userRequest = userRequest;
    }

    public static boolean shouldEnrichIssuePrompt(@Nullable String body) {
        return TextUtil.countWords(body) < ISSUE_PROMPT_ENRICHMENT_WORD_THRESHOLD;
    }

    public record IssueResult(String title, String bodyMarkdown, Context context) {}

    record ParsedIssue(String title, String bodyMarkdown) {}

    public IssueResult execute() throws InterruptedException {
        try (var scope = cm.beginTaskUngrouped("Issue Writer")) {
            String goal =
                    """
                    Issue Writer: produce a high-quality GitHub issue by discovering and citing evidence in this repository.

                    User request:
                    %s
                    """
                            .formatted(userRequest);

            var agent = new SearchAgent(context, goal, model, SearchPrompts.Objective.ISSUE_DIAGNOSIS, scope);
            var result = agent.execute();
            Context resultingContext = scope.append(result);

            var parsed = parseIssueResponse(result.stopDetails().explanation());

            String finalBodyMarkdown = maybeAnnotateDiffBlocks(parsed.bodyMarkdown());
            return new IssueResult(parsed.title(), finalBodyMarkdown, resultingContext);
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

    static ParsedIssue parseIssueResponse(String rawText) {
        if (rawText.isBlank()) {
            throw new IllegalArgumentException("parseIssueResponse: input text is blank");
        }

        logger.trace("parseIssueResponse: rawText length={}", rawText.length());

        JsonNode root;
        try {
            root = Json.getMapper().readTree(rawText);
        } catch (JacksonException initialParseError) {
            throw new IllegalArgumentException("parseIssueResponse: invalid JSON", initialParseError);
        }

        if (!root.has("title")
                || !root.get("title").isTextual()
                || root.get("title").asText().isBlank()) {
            throw new IllegalArgumentException("parseIssueResponse: missing or invalid 'title' field");
        }

        if (!root.has("body")
                || !root.get("body").isTextual()
                || root.get("body").asText().isBlank()) {
            throw new IllegalArgumentException("parseIssueResponse: missing or invalid 'body' field");
        }

        return new ParsedIssue(root.get("title").asText(), root.get("body").asText());
    }
}
