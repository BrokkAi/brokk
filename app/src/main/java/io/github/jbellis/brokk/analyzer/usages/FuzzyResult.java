package io.github.jbellis.brokk.analyzer.usages;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import java.util.List;

/**
 * Result type for usage queries.
 *
 * <ul>
 *   <li>Success: usages resolved (possibly empty)
 *   <li>Failure: unable to resolve query due to analyzer or LLM related issues
 *   <li>Ambiguous: short name or targets ambiguous; classification needed
 *   <li>TooManyCallsites: guardrail when raw callsites exceed cap
 * </ul>
 */
public sealed interface FuzzyResult
        permits FuzzyResult.Success, FuzzyResult.Failure, FuzzyResult.Ambiguous, FuzzyResult.TooManyCallsites {

    /** Successful resolution of usages (possibly empty). */
    record Success(List<UsageHit> hits) implements FuzzyResult {
        public Success(List<UsageHit> hits) {
            this.hits = List.copyOf(requireNonNull(hits, "hits"));
        }

        @Override
        public String toString() {
            return "Success{hits=" + hits.size() + "}";
        }
    }

    /** Failure: Error related to querying the analyzer or LLM. */
    record Failure(String fqName, String reason) implements FuzzyResult {
        @Override
        public String toString() {
            return "Failure{fqName=" + fqName + ", reason=" + reason + "}";
        }
    }

    /** Ambiguous result: indicates multiple candidate targets. */
    record Ambiguous(String shortName, List<CodeUnit> candidateTargets, List<UsageHit> hits) implements FuzzyResult {
        public Ambiguous(String shortName, List<CodeUnit> candidateTargets, List<UsageHit> hits) {
            this.shortName = requireNonNull(shortName, "shortName");
            this.candidateTargets = List.copyOf(requireNonNull(candidateTargets, "candidateTargets"));
            this.hits = List.copyOf(requireNonNull(hits, "hits"));
        }

        @Override
        public String toString() {
            return "Ambiguous{shortName=" + shortName + ", candidates=" + candidateTargets.size() + ", hits="
                    + hits.size() + "}";
        }
    }

    /** Too-many-callsites guardrail. */
    record TooManyCallsites(String shortName, int totalCallsites, int limit) implements FuzzyResult {
        public TooManyCallsites(String shortName, int totalCallsites, int limit) {
            this.shortName = requireNonNull(shortName, "shortName");
            this.totalCallsites = totalCallsites;
            this.limit = limit;
        }

        @Override
        public String toString() {
            return "TooManyCallsites{shortName="
                    + shortName
                    + ", totalCallsites="
                    + totalCallsites
                    + ", limit="
                    + limit
                    + "}";
        }
    }
}
