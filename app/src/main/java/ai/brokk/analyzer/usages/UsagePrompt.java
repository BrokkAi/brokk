package ai.brokk.analyzer.usages;

/**
 * Immutable prompt container for classifying a single usage candidate.
 *
 * <ul>
 *   <li>filterDescription: description of what we are searching for; intended for RelevanceClassifier.relevanceScore
 *   <li>candidateText: the snippet representing the single usage being classified
 *   <li>promptText: a fully rendered prompt (XML-like) for richer model inputs
 * </ul>
 */
public record UsagePrompt(String filterDescription, String candidateText, String promptText) {}
