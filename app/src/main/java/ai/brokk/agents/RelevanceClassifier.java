package ai.brokk.agents;

import ai.brokk.AbstractService;
import ai.brokk.Llm;
import ai.brokk.concurrent.AdaptiveExecutor;
import ai.brokk.util.IStringDiskCache;
import ai.brokk.util.StringDiskCache;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.*;
import java.util.concurrent.Callable;
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
            messages.add(new UserMessage(response));
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

    /**
     * Low-level API: ask the model to score the relevance of the candidate text to the filter as a real number between
     * 0.0 and 1.0 (inclusive). Retries on ambiguous responses.
     */
    public static double scoreRelevance(Llm llm, IStringDiskCache diskCache, String systemPrompt, String userPrompt)
            throws InterruptedException {
        var cacheKey = relevanceScoreCacheKey(llm, systemPrompt, userPrompt);

        var cached = diskCache.computeIfAbsentInterruptibly(
                cacheKey, () -> Double.toString(scoreRelevanceUncached(llm, systemPrompt, userPrompt)));
        return Double.parseDouble(cached);
    }

    private static double scoreRelevanceUncached(Llm llm, String systemPrompt, String userPrompt)
            throws InterruptedException {
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
            if (hasRel && !hasIrr) return 1.0;
            if (!hasRel && hasIrr) return 0.0;

            double parsed = extractScore(response);
            if (!Double.isNaN(parsed)) return parsed;

            logger.debug("Ambiguous scoring response, retrying...");
            messages.add(new UserMessage(response));
            messages.add(new UserMessage("Respond with only a single number between 0.0 and 1.0, inclusive."));
        }

        logger.debug("Defaulting to score=0.0 after {} attempts", MAX_RELEVANCE_TRIES);
        return 0.0;
    }

    /**
     * Convenience wrapper for scoring relevance. The {@code filterDescription} describes what we are looking for and
     * {@code candidateText} is the text to score.
     */
    public static double relevanceScore(
            Llm llm, IStringDiskCache diskCache, String filterDescription, String candidateText)
            throws InterruptedException {
        var systemPrompt =
                """
                           You are an assistant that scores how relevant the candidate text is,
                           given a user-provided filter description.
                           Respond with only a single number between 0.0 and 1.0 (inclusive),
                           where 0.0 means not relevant and 1.0 means highly relevant.
                           """;

        if (!candidateText.contains("</candidate>")) {
            candidateText = "<candidate>\n" + candidateText + "\n</candidate>";
        }
        var userPrompt =
                """
                         <filter>
                         %s
                         </filter>

                         %s

                         Output only a single number in [0.0, 1.0].
                         """
                        .formatted(filterDescription, candidateText);

        return scoreRelevance(llm, diskCache, systemPrompt, userPrompt);
    }

    /**
     * Sequentially scores a batch of relevance tasks. Reuses the same prompts and retry/parse logic as
     * scoreRelevance(). Preserves insertion order in the returned map.
     *
     * @param llm     the model to use for scoring
     * @param service the LLM service.
     * @param tasks   list of tasks to score
     * @return a map from task to relevance score in [0.0, 1.0]
     */
    public static Map<RelevanceTask, Double> relevanceScoreBatch(
            IStringDiskCache diskCache, Llm llm, AbstractService service, List<RelevanceTask> tasks)
            throws InterruptedException {
        if (tasks.isEmpty()) return Collections.emptyMap();

        var results = new HashMap<RelevanceTask, Double>();

        try (var executor = AdaptiveExecutor.create(service, llm.getModel(), tasks.size())) {
            var recommendationTasks = getRecommendationTasks(llm, diskCache, tasks);
            var futures = executor.invokeAll(recommendationTasks);

            for (var future : futures) {
                try {
                    var result = future.get();
                    results.put(result.task(), result.score());
                } catch (ExecutionException e) {
                    logger.error("Execution of a task failed while waiting for result", e);
                }
            }
        }

        return Map.copyOf(results);
    }

    private static List<Callable<RelevanceResult>> getRecommendationTasks(
            Llm llm, IStringDiskCache diskCache, List<RelevanceTask> tasks) {
        var recommendationTasks = new ArrayList<Callable<RelevanceResult>>();
        for (var task : tasks) {
            recommendationTasks.add(() -> {
                try {
                    return new RelevanceResult(
                            task, relevanceScore(llm, diskCache, task.filterDescription(), task.candidateText()));
                } catch (InterruptedException e) {
                    logger.error("Interrupted while determining score for {}. Defaulting to 1.0.", task, e);
                    return new RelevanceResult(task, 1d);
                }
            });
        }
        return recommendationTasks;
    }

    public static List<Double> scoreRelevanceMulti(
            Llm llm, IStringDiskCache diskCache, String systemPrompt, String userPrompt, int expectedCount)
            throws InterruptedException {
        var cacheKey = relevanceScoreCacheKey(llm, systemPrompt, userPrompt, expectedCount);
        var cached = diskCache.computeIfAbsentInterruptibly(
                cacheKey, () -> scoreRelevanceMultiUncached(llm, systemPrompt, userPrompt, expectedCount).stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(",")));
        return Arrays.stream(cached.split(","))
                .map(String::strip)
                .map(Double::parseDouble)
                .toList();
    }

    private static List<Double> scoreRelevanceMultiUncached(
            Llm llm, String systemPrompt, String userPrompt, int expectedCount) throws InterruptedException {
        List<ChatMessage> messages = new ArrayList<>(2);
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userPrompt));

        for (int attempt = 1; attempt <= MAX_RELEVANCE_TRIES; attempt++) {
            logger.trace("Invoking relevance multi-scorer (attempt {}/{})", attempt, MAX_RELEVANCE_TRIES);
            var result = llm.sendRequest(messages);

            if (result.error() != null) {
                logger.debug("Error scoring response (attempt {}): {}", attempt, result);
                continue;
            }

            var response = result.text().strip();
            logger.trace("Relevance multi-scorer response (attempt {}): {}", attempt, response);

            // Accept marker-based outputs as degenerate cases
            boolean hasRel = response.contains(RELEVANT_MARKER);
            boolean hasIrr = response.contains(IRRELEVANT_MARKER);
            if (hasRel && !hasIrr) return Collections.nCopies(expectedCount, 1.0);
            if (!hasRel && hasIrr) return Collections.nCopies(expectedCount, 0.0);

            var parsed = extractScores(response, expectedCount);
            if (!parsed.isEmpty()) return parsed;

            logger.debug("Ambiguous multi-scoring response, retrying...");
            messages.add(new UserMessage(response));
            messages.add(
                    new UserMessage("Respond with only a JSON array of %d numbers between 0.0 and 1.0, e.g. [0.8, 0.3]."
                            .formatted(expectedCount)));
        }

        logger.debug("Defaulting to all-zeros after {} attempts", MAX_RELEVANCE_TRIES);
        return Collections.nCopies(expectedCount, 0.0);
    }

    public static List<Double> relevanceScoreMulti(
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

        return scoreRelevanceMulti(llm, diskCache, systemPrompt, userPrompt, expectedCount);
    }

    public static Map<RelevanceTask, List<Double>> relevanceScoreBatchMulti(
            IStringDiskCache diskCache, Llm llm, AbstractService service, List<RelevanceTask> tasks)
            throws InterruptedException {
        if (tasks.isEmpty()) return Collections.emptyMap();

        var results = new HashMap<RelevanceTask, List<Double>>();

        try (var executor = AdaptiveExecutor.create(service, llm.getModel(), tasks.size())) {
            var recommendationTasks = getRecommendationTasksMulti(llm, diskCache, tasks);
            var futures = executor.invokeAll(recommendationTasks);

            for (var future : futures) {
                try {
                    var result = future.get();
                    results.put(result.task(), result.scores());
                } catch (ExecutionException e) {
                    logger.error("Execution of a task failed while waiting for result", e);
                }
            }
        }

        return Map.copyOf(results);
    }

    private record RelevanceResultMulti(RelevanceTask task, List<Double> scores) {}

    private static List<Callable<RelevanceResultMulti>> getRecommendationTasksMulti(
            Llm llm, IStringDiskCache diskCache, List<RelevanceTask> tasks) {
        var recommendationTasks = new ArrayList<Callable<RelevanceResultMulti>>();
        for (var task : tasks) {
            recommendationTasks.add(() -> {
                try {
                    return new RelevanceResultMulti(
                            task,
                            relevanceScoreMulti(
                                    llm,
                                    diskCache,
                                    task.filterDescription(),
                                    task.candidateText(),
                                    task.expectedScoreCount()));
                } catch (InterruptedException e) {
                    logger.error("Interrupted while determining scores for {}. Defaulting to all 1.0.", task, e);
                    return new RelevanceResultMulti(task, Collections.nCopies(task.expectedScoreCount(), 1d));
                }
            });
        }
        return recommendationTasks;
    }

    static List<Double> extractScores(String response, int expectedCount) {
        if (response.isEmpty()) return List.of();

        // Try to find a bracketed list like [0.8, 0.3, 0.1]
        try {
            Pattern arrayPattern = Pattern.compile("\\[([\\d.,\\s+\\-eE]+)\\]");
            Matcher m = arrayPattern.matcher(response);
            if (m.find()) {
                String inner = m.group(1);
                String[] parts = inner.split(",");
                var scores = new ArrayList<Double>(parts.length);
                for (String part : parts) {
                    String trimmed = part.strip();
                    if (!trimmed.isEmpty()) {
                        scores.add(clamp01(Double.parseDouble(trimmed)));
                    }
                }
                if (scores.size() == expectedCount) {
                    return List.copyOf(scores);
                }
            }
        } catch (Throwable t) {
            logger.trace("Failed to parse array-style scores", t);
        }

        // Fallback: try single score and replicate
        double single = extractScore(response);
        if (!Double.isNaN(single)) {
            return Collections.nCopies(expectedCount, single);
        }

        return List.of();
    }

    private static double extractScore(String response) {
        if (response.isEmpty()) return Double.NaN;

        // Try JSON-like "score": <num>
        try {
            Pattern p = Pattern.compile("\"score\"\\s*:\\s*([-+]?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(response);
            if (m.find()) {
                double v = Double.parseDouble(m.group(1));
                return clamp01(v);
            }
        } catch (Throwable t) {
            logger.trace("Failed to parse JSON-style score", t);
        }

        // Try first number present
        try {
            Pattern p = Pattern.compile("([-+]?\\d+(?:\\.\\d+)?)");
            Matcher m = p.matcher(response);
            if (m.find()) {
                double v = Double.parseDouble(m.group(1));
                return clamp01(v);
            }
        } catch (Throwable t) {
            logger.trace("Failed to extract numeric score", t);
        }

        return Double.NaN;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private static String relevanceScoreCacheKey(Llm llm, String systemPrompt, String userPrompt) {
        return relevanceScoreCacheKey(llm, systemPrompt, userPrompt, 1);
    }

    private static String relevanceScoreCacheKey(Llm llm, String systemPrompt, String userPrompt, int expectedCount) {
        var payload = llm + "\n" + systemPrompt + "\n---\n" + userPrompt + "\ncount=" + expectedCount;
        return "relevance_" + StringDiskCache.sha1Hex(payload);
    }
}
