package io.github.jbellis.brokk.analyzer.usages;

/**
 * Immutable prompt container for classifying a single usage candidate.
 *
 * - filterDescription: description of what we are searching for; intended for RelevanceClassifier.relevanceScore
 * - candidateText: the snippet representing the single usage being classified
 * - promptText: a fully rendered prompt (XML-like) for richer model inputs
 */
public record UsagePrompt(String filterDescription, String candidateText, String promptText) {}
