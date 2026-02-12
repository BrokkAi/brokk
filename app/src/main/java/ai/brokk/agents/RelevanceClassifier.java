package ai.brokk.agents;

import ai.brokk.AbstractService;
import ai.brokk.Llm;
import ai.brokk.concurrent.AdaptiveExecutor;
import ai.brokk.util.IStringDiskCache;
import ai.brokk.util.StringDiskCache;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Utility to classify relevance of text according to a filter prompt, re-usable across agents. */
public final class RelevanceClassifier {
    private static final Logger logger = LogManager.getLogger(RelevanceClassifier.class);

    public static final String RELEVANT_MARKER = "BRK_RELEVANT";
    public static final String IRRELEVANT_MARKER = "BRK_IRRELEVANT";
    private static final int MAX_RELEVANCE_TRIES = 3;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private RelevanceClassifier() {}

    public static boolean classifyRelevant(Llm llm, String systemPrompt, String userPrompt)
            throws InterruptedException {
        List<ChatMessage> messages = new ArrayList<>(2);
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userPrompt));

        for (int attempt = 1; attempt <= MAX_RELEVANCE_TRIES; attempt++) {
            logger.trace("Invoking relevance classifier (attempt {}/{})", attempt, MAX_RELEVANCE_TRIES);
            var result = llm.sendRequest(messages);

            if (result.error() != null) {
                logger.debug("Error relevance response (attempt {}): {}", attempt, result);
                continue;
            }

            var response = result.text().strip();
            logger.trace("Relevance classifier response (attempt {}): {}", attempt, response);

            boolean hasRel = response.contains(RELEVANT_MARKER);
            boolean hasIrr = response.contains(IRRELEVANT_MARKER);

            if (hasRel && !hasIrr) return true;
            if (!hasRel && hasIrr) return false;

            logger.debug("Ambiguous relevance response, retrying...");
            messages.add(new AiMessage(response));
            messages.add(new UserMessage("You must respond with exactly one of the markers {%s, %s}"
                    .formatted(RELEVANT_MARKER, IRRELEVANT_MARKER)));
        }

        logger.debug("Defaulting to NOT relevant after {} attempts", MAX_RELEVANCE_TRIES);
        return false;
    }

    /**
     * Convenience wrapper that hides the relevance markers and prompt-crafting details from callers. The
     * {@code filterDescription} describes what we are looking for (e.g. user instructions or a free-form filter) and
     * {@code candidateText} is the text whose relevance we want to judge.
     */
    public static boolean isRelevant(Llm llm, String filterDescription, String candidateText)
            throws InterruptedException {
        var systemPrompt =
                """
                           You are an assistant that determines if the candidate text is relevant,
                           given a user-provided filter description.
                           Conclude with %s if the text is relevant, or %s if it is not.
                           """
                        .formatted(RELEVANT_MARKER, IRRELEVANT_MARKER);

        if (!candidateText.contains("</candidate>")) {
            candidateText = "<candidate>\n" + candidateText + "\n</candidate>";
        }

        var userPrompt =
                """
                         <filter>
                         %s
                         </filter>

                         %s

                         Is the candidate relevant, as determined by the filter?  Respond with exactly one
                         of the markers %s or %s.
                         """
                        .formatted(filterDescription, candidateText, RELEVANT_MARKER, IRRELEVANT_MARKER);

        return classifyRelevant(llm, systemPrompt, userPrompt);
    }

    public static List<Double> scoreRelevance(
            Llm llm, IStringDiskCache diskCache, String systemPrompt, String userPrompt, int expectedCount)
            throws InterruptedException {
        var cacheKey = relevanceScoreCacheKey(llm, systemPrompt, userPrompt, expectedCount);
        var cached = diskCache.computeIfAbsentInterruptibly(
                cacheKey, () -> scoreRelevanceUncached(llm, systemPrompt, userPrompt, expectedCount).stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(",")));
        return Arrays.stream(cached.split(","))
                .map(String::strip)
                .map(Double::parseDouble)
                .toList();
    }

    private static List<Double> scoreRelevanceUncached(
            Llm llm, String systemPrompt, String userPrompt, int expectedCount) throws InterruptedException {
        List<ChatMessage> messages = new ArrayList<>(2);
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userPrompt));

        for (int attempt = 1; attempt <= MAX_RELEVANCE_TRIES; attempt++) {
            logger.trace("Invoking relevance scorer (attempt {}/{})", attempt, MAX_RELEVANCE_TRIES);
            var result = llm.sendRequest(messages);

            if (result.error() != null) {
                logger.debug("Error scoring response (attempt {}): {}", attempt, result);
                continue;
            }

            var response = result.text().strip();
            logger.trace("Relevance scorer response (attempt {}): {}", attempt, response);

            // Accept marker-based outputs as degenerate cases
            boolean hasRel = response.contains(RELEVANT_MARKER);
            boolean hasIrr = response.contains(IRRELEVANT_MARKER);
            if (hasRel && !hasIrr) return Collections.nCopies(expectedCount, 1.0);
            if (!hasRel && hasIrr) return Collections.nCopies(expectedCount, 0.0);

            var parsed = extractScores(response, expectedCount);
            if (!parsed.isEmpty()) return parsed;

            logger.debug("Ambiguous scoring response, retrying...");
            messages.add(new AiMessage(response));
            messages.add(
                    new UserMessage("Respond with only a JSON array of %d numbers between 0.0 and 1.0, e.g. [0.8, 0.3]."
                            .formatted(expectedCount)));
        }

        logger.debug("Defaulting to all-zeros after {} attempts", MAX_RELEVANCE_TRIES);
        return Collections.nCopies(expectedCount, 0.0);
    }

    public static List<Double> relevanceScore(
            Llm llm, IStringDiskCache diskCache, String filterDescription, String candidateText, int expectedCount)
            throws InterruptedException {
        var systemPrompt =
                """
                           You are an assistant that scores how relevant the candidate text is,
                           given a user-provided filter description.
                           Respond with only a JSON array of %d numbers between 0.0 and 1.0 (inclusive),
                           where 0.0 means not relevant and 1.0 means highly relevant.
                           """
                        .formatted(expectedCount);

        if (!candidateText.contains("</candidate>")) {
            candidateText = "<candidate>\n" + candidateText + "\n</candidate>";
        }
        var userPrompt =
                """
                         <filter>
                         %s
                         </filter>

                         %s

                         Output only a JSON array of %d numbers in [0.0, 1.0].
                         """
                        .formatted(filterDescription, candidateText, expectedCount);

        return scoreRelevance(llm, diskCache, systemPrompt, userPrompt, expectedCount);
    }

    public static Map<RelevanceTask, List<Double>> relevanceScoreBatch(
            IStringDiskCache diskCache, Llm llm, AbstractService service, List<RelevanceTask> tasks)
            throws InterruptedException {
        if (tasks.isEmpty()) return Collections.emptyMap();

        var results = new ConcurrentHashMap<RelevanceTask, List<Double>>();

        try (var executor = AdaptiveExecutor.create(service, llm.getModel(), tasks.size())) {
            List<Callable<Void>> callables = tasks.stream()
                    .<Callable<Void>>map(task -> () -> {
                        try {
                            var scores = relevanceScore(
                                    llm,
                                    diskCache,
                                    task.filterDescription(),
                                    task.candidateText(),
                                    task.expectedScoreCount());
                            results.put(task, scores);
                        } catch (InterruptedException e) {
                            logger.error(
                                    "Interrupted while determining scores for {}. Defaulting to all 0.0.", task, e);
                            results.put(task, Collections.nCopies(task.expectedScoreCount(), 0.0));
                        }
                        return null;
                    })
                    .toList();

            var futures = executor.invokeAll(callables);
            for (var future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    logger.error("Execution of a task failed while waiting for result", e);
                }
            }
        }

        return Map.copyOf(results);
    }

    static List<Double> extractScores(String response, int expectedCount) {
        if (response.isEmpty()) return List.of();

        // Try to find and parse a JSON array like [0.8, 0.3, 0.1]
        int start = response.indexOf('[');
        if (start != -1) {
            int end = response.indexOf(']', start);
            if (end != -1) {
                try {
                    String json = response.substring(start, end + 1);
                    List<Double> parsed = objectMapper.readValue(json, new TypeReference<>() {});
                    if (parsed != null) {
                        if (parsed.size() == expectedCount) {
                            return parsed.stream()
                                    .map(RelevanceClassifier::clamp01)
                                    .toList();
                        } else {
                            logger.debug(
                                    "JSON array size mismatch: expected {}, got {}. Rejecting response.",
                                    expectedCount,
                                    parsed.size());
                        }
                    }
                } catch (JacksonException | RuntimeException e) {
                    logger.trace("Failed to parse array-style scores via Jackson", e);
                }
            }
        }

        // Lenient parsing: if we only expect 1 score, accept a bare number or JSON-style "score": X
        if (expectedCount == 1) {
            // Try JSON-like "score": <num>
            try {
                Pattern p = Pattern.compile("\"score\"\\s*:\\s*([-+]?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(response);
                if (m.find()) {
                    return List.of(clamp01(Double.parseDouble(m.group(1))));
                }
            } catch (RuntimeException e) {
                logger.trace("Failed to parse JSON-style score", e);
            }

            // Try first number present
            try {
                Pattern p = Pattern.compile("([-+]?\\d+(?:\\.\\d+)?)");
                Matcher m = p.matcher(response);
                if (m.find()) {
                    return List.of(clamp01(Double.parseDouble(m.group(1))));
                }
            } catch (RuntimeException e) {
                logger.trace("Failed to extract numeric score", e);
            }
        }

        return List.of();
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        if (v < 0.0) return 0.0;
        return Math.min(v, 1.0);
    }

    private static String relevanceScoreCacheKey(Llm llm, String systemPrompt, String userPrompt, int expectedCount) {
        var payload = llm + "\n" + systemPrompt + "\n---\n" + userPrompt + "\ncount=" + expectedCount;
        return "relevance_" + StringDiskCache.sha1Hex(payload);
    }
}
