package io.github.jbellis.brokk.agents;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.Llm;
import java.util.*;
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
    private static final int MAX_BATCH_TASKS = 10;

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
                        .formatted(RELEVANT_MARKER, IRRELEVANT_MARKER)
                        .stripIndent();

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
                        .formatted(filterDescription, candidateText, RELEVANT_MARKER, IRRELEVANT_MARKER)
                        .stripIndent();

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
                           """
                        .stripIndent();

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
                        .formatted(filterDescription, candidateText)
                        .stripIndent();

        return scoreRelevance(llm, systemPrompt, userPrompt);
    }

    /**
     * Sequentially scores a batch of relevance tasks. Reuses the same prompts and retry/parse logic as
     * scoreRelevance(). Preserves insertion order in the returned map.
     *
     * @param llm the model to use for scoring
     * @param tasks list of tasks to score
     * @return a map from task to relevance score in [0.0, 1.0]
     */
    public static Map<RelevanceTask, Double> relevanceScoreBatch(Llm llm, List<RelevanceTask> tasks)
            throws InterruptedException {
        if (tasks.isEmpty()) return Collections.emptyMap();

        logger.trace("Invoking relevance scorer for batch (size={})", tasks.size());

        // Output map preserving insertion order across all chunks
        var results = new LinkedHashMap<RelevanceTask, Double>(Math.max(16, tasks.size() * 2));

        // Process in chunks to ensure at most MAX_BATCH_TASKS tasks per LLM call
        for (int offset = 0; offset < tasks.size(); offset += MAX_BATCH_TASKS) {
            int end = Math.min(offset + MAX_BATCH_TASKS, tasks.size());
            var chunk = tasks.subList(offset, end);
            logger.trace("Scoring batch chunk offset={}, size={}", offset, chunk.size());

            // Build ephemeral IDs for each task in this chunk: T1..Tn
            var ids = new ArrayList<String>(chunk.size());
            for (int i = 0; i < chunk.size(); i++) {
                ids.add("T" + (i + 1));
            }

            // System prompt for a strict JSON object mapping id -> score
            var systemPrompt =
                    """
                               You are an assistant that scores how relevant candidate texts are,
                               given user-provided filter descriptions.
                               For each task, produce a relevance score between 0.0 and 1.0 (inclusive),
                               where 0.0 means not relevant and 1.0 means highly relevant.

                               Respond ONLY with a compact JSON object that maps each task id (a string) to its numeric score.
                               Do not include any extra commentary or text. Example:
                               {"T1": 0.72, "T2": 0.05}
                               """
                            .stripIndent();

            // Build user prompt with tasks in this chunk
            var sb = new StringBuilder();
            sb.append("<tasks>\n");
            for (int i = 0; i < chunk.size(); i++) {
                var t = chunk.get(i);
                var id = ids.get(i);
                sb.append("<task id=\"").append(id).append("\">\n");
                sb.append("<filter>\n").append(t.filterDescription()).append("\n</filter>\n\n");
                sb.append("<candidate>\n").append(t.candidateText()).append("\n</candidate>\n");
                sb.append("</task>\n\n");
            }
            sb.append("</tasks>\n");

            var userPrompt =
                    """
                             %s

                             Produce only a JSON object mapping the task ids to scores in [0.0, 1.0].
                             """
                            .formatted(sb.toString())
                            .stripIndent();

            // Prepare chat messages for this chunk
            List<ChatMessage> messages = new ArrayList<>(2);
            messages.add(new SystemMessage(systemPrompt));
            messages.add(new UserMessage(userPrompt));

            Map<String, Double> idToScore = null;

            for (int attempt = 1; attempt <= MAX_RELEVANCE_TRIES; attempt++) {
                logger.trace(
                        "Invoking batch relevance scorer (chunk offset={}, attempt {}/{})",
                        offset,
                        attempt,
                        MAX_RELEVANCE_TRIES);
                var result = llm.sendRequest(messages);

                if (result.error() != null) {
                    logger.debug(
                            "Error batch scoring response (chunk offset={}, attempt {}): {}", offset, attempt, result);
                    continue;
                }

                var response = result.text().strip();
                logger.trace(
                        "Batch relevance scorer response (chunk offset={}, attempt {}): {}", offset, attempt, response);

                // Try to parse a JSON-like object with "Ti": <num> pairs
                var parsed = new LinkedHashMap<String, Double>(ids.size());
                boolean allFound = true;
                for (var id : ids) {
                    try {
                        Pattern p =
                                Pattern.compile("\"%s\"\\s*:\\s*([-+]?\\d+(?:\\.\\d+)?)".formatted(Pattern.quote(id)));
                        Matcher m = p.matcher(response);
                        if (m.find()) {
                            double v = Double.parseDouble(m.group(1));
                            parsed.put(id, clamp01(v));
                        } else {
                            allFound = false;
                            break;
                        }
                    } catch (Throwable t) {
                        logger.trace("Failed parsing score for id {}", id, t);
                        allFound = false;
                        break;
                    }
                }

                if (!allFound) {
                    // Fallback: extract the first N numbers from the response, in order, and map to task order
                    try {
                        Pattern np = Pattern.compile("([-+]?\\d+(?:\\.\\d+)?)");
                        Matcher nm = np.matcher(response);
                        var nums = new ArrayList<Double>(ids.size());
                        while (nm.find() && nums.size() < ids.size()) {
                            nums.add(clamp01(Double.parseDouble(nm.group(1))));
                        }
                        if (nums.size() == ids.size()) {
                            parsed.clear();
                            for (int i = 0; i < ids.size(); i++) {
                                parsed.put(ids.get(i), nums.get(i));
                            }
                            allFound = true;
                        }
                    } catch (Throwable t) {
                        logger.trace("Failed numeric-fallback parse for batch response", t);
                    }
                }

                if (allFound) {
                    idToScore = parsed;
                    break;
                }

                logger.debug("Ambiguous batch scoring response for chunk offset={}, retrying...", offset);
                messages.add(new UserMessage(response));
                messages.add(
                        new UserMessage(
                                "Respond ONLY with a strict JSON object mapping the task id strings to numeric scores "
                                        + "between 0.0 and 1.0 inclusive. For example: {\"T1\": 0.42, \"T2\": 0.0}. No additional text."));
            }

            // Append results for this chunk preserving insertion order; default to 0.0 for any missing
            for (int i = 0; i < chunk.size(); i++) {
                var task = chunk.get(i);
                var id = ids.get(i);
                double score = (idToScore != null && idToScore.containsKey(id)) ? idToScore.get(id) : 0.0;
                results.put(task, score);
            }
            if (idToScore == null) {
                logger.debug(
                        "Defaulted chunk scores to 0.0 after {} attempts (chunk offset={})",
                        MAX_RELEVANCE_TRIES,
                        offset);
            }
        }

        return Map.copyOf(results);
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
