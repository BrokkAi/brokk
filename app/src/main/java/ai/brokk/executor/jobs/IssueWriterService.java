package ai.brokk.executor.jobs;

import ai.brokk.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class IssueWriterService {
    private static final Logger logger = LogManager.getLogger(IssueWriterService.class);

    private IssueWriterService() {
        throw new UnsupportedOperationException("Utility class");
    }

    public record IssueResponse(String title, String bodyMarkdown) {}

    public static @Nullable IssueResponse parseIssueResponse(@Nullable String rawText) {
        if (rawText == null || rawText.isBlank()) {
            logger.warn("parseIssueResponse: empty or null input");
            return null;
        }

        logger.trace("parseIssueResponse: rawText length={}", rawText.length());

        JsonNode root;
        try {
            root = Json.getMapper().readTree(rawText);
        } catch (Exception initialParseError) {
            logger.trace("parseIssueResponse: direct parse failed, attempting extraction");
            int firstBrace = rawText.indexOf('{');
            int lastBrace = rawText.lastIndexOf('}');

            if (firstBrace == -1 || lastBrace == -1 || lastBrace < firstBrace) {
                logger.warn("parseIssueResponse: no JSON braces found in response", initialParseError);
                return null;
            }

            String extractedJson = rawText.substring(firstBrace, lastBrace + 1);
            try {
                root = Json.getMapper().readTree(extractedJson);
                logger.trace("parseIssueResponse: extraction succeeded");
            } catch (Exception extractionParseError) {
                logger.warn("parseIssueResponse: extracted JSON is malformed", extractionParseError);
                return null;
            }
        }

        if (!root.has("title") || !root.get("title").isTextual()) {
            logger.warn("parseIssueResponse: missing or invalid 'title' field");
            return null;
        }

        if (!root.has("bodyMarkdown") || !root.get("bodyMarkdown").isTextual()) {
            logger.warn("parseIssueResponse: missing or invalid 'bodyMarkdown' field");
            return null;
        }

        return new IssueResponse(
                root.get("title").asText(), root.get("bodyMarkdown").asText());
    }
}
