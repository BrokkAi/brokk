package io.github.jbellis.brokk.analyzer.usages;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import java.util.List;

/**
 * Result type for usage queries.
 *
 * <p>- Success: usages resolved (possibly empty) - Ambiguous: short name or targets ambiguous; classification needed -
 * TooManyCallsites: guardrail when raw callsites exceed cap
 */
public sealed interface FuzzyResult permits FuzzyResult.Success, FuzzyResult.Ambiguous, FuzzyResult.TooManyCallsites {

    /** Successful resolution of usages (possibly empty). */
    final class Success implements FuzzyResult {
        private final List<FuzzyUsageAnalyzer.UsageHit> hits;

        public Success(List<FuzzyUsageAnalyzer.UsageHit> hits) {
            this.hits = List.copyOf(requireNonNull(hits, "hits"));
        }

        public List<FuzzyUsageAnalyzer.UsageHit> hits() {
            return hits;
        }

        @Override
        public String toString() {
            return "Success{hits=" + hits.size() + "}";
        }
    }

    /** Ambiguous result: indicates multiple candidate targets and prepared callsites. */
    final class Ambiguous implements FuzzyResult {
        private final String shortName;
        private final List<CodeUnit> candidateTargets;
        private final int preparedCallsites;

        public Ambiguous(String shortName, List<CodeUnit> candidateTargets, int preparedCallsites) {
            this.shortName = requireNonNull(shortName, "shortName");
            this.candidateTargets = List.copyOf(requireNonNull(candidateTargets, "candidateTargets"));
            this.preparedCallsites = preparedCallsites;
        }

        public String shortName() {
            return shortName;
        }

        public List<CodeUnit> candidateTargets() {
            return candidateTargets;
        }

        public int preparedCallsites() {
            return preparedCallsites;
        }

        @Override
        public String toString() {
            return "Ambiguous{shortName="
                    + shortName
                    + ", candidates="
                    + candidateTargets.size()
                    + ", preparedCallsites="
                    + preparedCallsites
                    + "}";
        }
    }

    /** Too-many-callsites guardrail. */
    final class TooManyCallsites implements FuzzyResult {
        private final String shortName;
        private final int totalCallsites;
        private final int limit;

        public TooManyCallsites(String shortName, int totalCallsites, int limit) {
            this.shortName = requireNonNull(shortName, "shortName");
            this.totalCallsites = totalCallsites;
            this.limit = limit;
        }

        public String shortName() {
            return shortName;
        }

        public int totalCallsites() {
            return totalCallsites;
        }

        public int limit() {
            return limit;
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
