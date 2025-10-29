package ai.brokk.agents;

import ai.brokk.Llm;
import ai.brokk.Service;
import ai.brokk.util.AdaptiveExecutor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

        var userPrompt =
                """
                         <filter>
                         %s
                         </filter>

                         <candidate>
                         %s
                         </candidate>

                         Is the candidate text relevant, as determined by the filter?  Respond with exactly one
                         of the markers %s or %s.
                         """
                        .formatted(filterDescription, candidateText, RELEVANT_MARKER, IRRELEVANT_MARKER);

        return classifyRelevant(llm, systemPrompt, userPrompt);
    }

    /**
     * Low-level API: ask the model to score the relevance of the candidate text to the filter as a real number between
     * 0.0 and 1.0 (inclusive). Retries on ambiguous responses.
     */
    public static double scoreRelevance(Llm llm, String systemPrompt, String userPrompt) throws InterruptedException {
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
    public static double relevanceScore(Llm llm, String filterDescription, String candidateText)
            throws InterruptedException {
        var systemPrompt =
                """
                           You are an assistant that scores how relevant the candidate text is,
                           given a user-provided filter description.
                           Respond with only a single number between 0.0 and 1.0 (inclusive),
                           where 0.0 means not relevant and 1.0 means highly relevant.
                           """;

        var userPrompt =
                """
                         <filter>
                         %s
                         </filter>

                         <candidate>
                         %s
                         </candidate>

                         Output only a single number in [0.0, 1.0].
                         """
                        .formatted(filterDescription, candidateText);

        return scoreRelevance(llm, systemPrompt, userPrompt);
    }

    /**
     * Sequentially scores a batch of relevance tasks. Reuses the same prompts and retry/parse logic as
     * scoreRelevance(). Preserves insertion order in the returned map.
     *
     * @param llm the model to use for scoring
     * @param service the LLM service.
     * @param tasks list of tasks to score
     * @return a map from task to relevance score in [0.0, 1.0]
     */
    public static Map<RelevanceTask, Double> relevanceScoreBatch(Llm llm, Service service, List<RelevanceTask> tasks)
            throws InterruptedException {
        if (tasks.isEmpty()) return Collections.emptyMap();

        var results = new HashMap<RelevanceTask, Double>();

        try (var executor = AdaptiveExecutor.create(service, llm.getModel(), tasks.size())) {
            var recommendationTasks = getRecommendationTasks(llm, tasks);
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

    private static List<Callable<RelevanceResult>> getRecommendationTasks(Llm llm, List<RelevanceTask> tasks) {
        var recommendationTasks = new ArrayList<Callable<RelevanceResult>>();
        for (var task : tasks) {
            recommendationTasks.add(() -> {
                try {
                    return new RelevanceResult(
                            task, relevanceScore(llm, task.filterDescription(), task.candidateText()));
                } catch (InterruptedException e) {
                    logger.error("Interrupted while determining score for {}. Defaulting to 1.0.", task, e);
                    return new RelevanceResult(task, 1d);
                }
            });
        }
        return recommendationTasks;
    }

    /**
     * Batch boolean relevance classification. Preserves insertion order in the returned map.
     *
     * @param llm the model to use
     * @param service the LLM service
     * @param tasks tasks to classify
     * @return a map from task to boolean relevance
     */
    public static Map<RelevanceTask, Boolean> relevanceBooleanBatch(Llm llm, Service service, List<RelevanceTask> tasks)
            throws InterruptedException {
        if (tasks.isEmpty()) return Collections.emptyMap();

        var results = new HashMap<RelevanceTask, Boolean>();

        try (var executor = AdaptiveExecutor.create(service, llm.getModel(), tasks.size())) {
            var booleanTasks = getBooleanTasks(llm, tasks);
            var futures = executor.invokeAll(booleanTasks);

            for (var future : futures) {
                try {
                    var result = future.get();
                    results.put(result.task(), result.relevant());
                } catch (ExecutionException e) {
                    logger.error("Execution of a boolean task failed while waiting for result", e);
                }
            }
        }

        return Map.copyOf(results);
    }

    private static List<Callable<BoolRelevanceResult>> getBooleanTasks(Llm llm, List<RelevanceTask> tasks) {
        var booleanTasks = new ArrayList<Callable<BoolRelevanceResult>>();
        for (var task : tasks) {
            booleanTasks.add(() -> {
                try {
                    return new BoolRelevanceResult(
                            task, isRelevant(llm, task.filterDescription(), task.candidateText()));
                } catch (InterruptedException e) {
                    logger.error(
                            "Interrupted while determining boolean relevance for {}. Defaulting to true.", task, e);
                    return new BoolRelevanceResult(task, true);
                }
            });
        }
        return booleanTasks;
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
}
