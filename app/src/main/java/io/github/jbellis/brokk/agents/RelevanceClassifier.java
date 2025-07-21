package io.github.jbellis.brokk.agents;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.Llm;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility to classify relevance of text according to a filter prompt, re-usable across agents.
 */
public final class RelevanceClassifier
{
    private static final Logger logger = LogManager.getLogger(RelevanceClassifier.class);

    public static final String RELEVANT_MARKER   = "BRK_RELEVANT";
    public static final String IRRELEVANT_MARKER = "BRK_IRRELEVANT";
    private static final int MAX_RELEVANCE_TRIES = 3;

    private RelevanceClassifier() { }

    public static boolean classifyRelevant(Llm llm,
                                           String systemPrompt,
                                           String userPrompt) throws InterruptedException
    {
        List<ChatMessage> messages = new ArrayList<>(2);
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userPrompt));

        for (int attempt = 1; attempt <= MAX_RELEVANCE_TRIES; attempt++) {
            logger.trace("Invoking relevance classifier (attempt {}/{})", attempt, MAX_RELEVANCE_TRIES);
            var result = llm.sendRequest(messages);

            if (result.error() != null || result.isEmpty()) {
                logger.debug("Empty/error relevance response (attempt {}): {}", attempt, result);
                continue;
            }

            var response = result.text().strip();
            logger.trace("Relevance classifier response (attempt {}): {}", attempt, response);

            boolean hasRel  = response.contains(RELEVANT_MARKER);
            boolean hasIrr  = response.contains(IRRELEVANT_MARKER);

            if (hasRel && !hasIrr)  return true;
            if (!hasRel && hasIrr)  return false;

            logger.debug("Ambiguous relevance response, retrying...");
            messages.add(new UserMessage(response));
            messages.add(new UserMessage(
                    "You must respond with exactly one of the markers {%s, %s}"
                            .formatted(RELEVANT_MARKER, IRRELEVANT_MARKER)));
        }

        logger.debug("Defaulting to NOT relevant after {} attempts", MAX_RELEVANCE_TRIES);
        return false;
    }

    /**
     * Convenience wrapper that hides the relevance markers and prompt-crafting
     * details from callers.  The {@code filterDescription} describes what we are
     * looking for (e.g. user instructions or a free-form filter) and
     * {@code candidateText} is the text whose relevance we want to judge.
     */
    public static boolean isRelevant(Llm llm,
                                     String filterDescription,
                                     String candidateText) throws InterruptedException
    {
        var systemPrompt = """
                           You are an assistant that determines if the candidate text is relevant,
                           given a user-provided filter description.
                           Conclude with %s if the text is relevant, or %s if it is not.
                           """.formatted(RELEVANT_MARKER, IRRELEVANT_MARKER).stripIndent();

        var userPrompt = """
                         <filter>
                         %s
                         </filter>

                         <candidate>
                         %s
                         </candidate>

                         Is the candidate text relevant, as determined by the filter?  Respond with exactly one
                         of the markers %s or %s.
                         """.formatted(filterDescription,
                                       candidateText,
                                       RELEVANT_MARKER,
                                       IRRELEVANT_MARKER).stripIndent();

        return classifyRelevant(llm, systemPrompt, userPrompt);
    }
}
