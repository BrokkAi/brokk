package io.github.jbellis.brokk.agents;

/** Result holder for boolean relevance classification. */
public record BoolRelevanceResult(RelevanceTask task, boolean relevant) {}
