package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.CodeUnit;
import java.util.*;
import org.jetbrains.annotations.Nullable;

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

    double CONFIDENCE_THRESHOLD = 0.5;

    record EitherUsagesOrError(@Nullable Set<UsageHit> usages, @Nullable String errorMessage) {

        public static EitherUsagesOrError from(String reason) {
            return new EitherUsagesOrError(null, reason);
        }

        public static EitherUsagesOrError from(Set<UsageHit> usages) {
            return new EitherUsagesOrError(usages, null);
        }

        public boolean hasUsages() {
            return usages != null;
        }

        public boolean hasErrorMessage() {
            return errorMessage != null;
        }

        public String getErrorMessage() {
            Objects.requireNonNull(errorMessage);
            return errorMessage;
        }

        public Set<UsageHit> getUsages() {
            Objects.requireNonNull(usages);
            return usages;
        }
    }

    default EitherUsagesOrError toEither() {
        if (this instanceof FuzzyResult.Failure failure) {
            return EitherUsagesOrError.from("No relevant usages found for symbol: " + failure.reason());
        } else if (this instanceof FuzzyResult.TooManyCallsites tooManyCallsites) {
            return EitherUsagesOrError.from("Too many call sites for symbol: " + tooManyCallsites.totalCallsites()
                    + "(limit " + tooManyCallsites.limit() + ")");
        }

        Set<UsageHit> uses = new HashSet<>();
        if (this instanceof FuzzyResult.Success(Set<UsageHit> hits)) {
            uses.addAll(hits);
        } else if (this instanceof FuzzyResult.Ambiguous ambiguous) {
            var filteredHits = ambiguous.hits().stream()
                    .filter(x -> x.confidence() >= CONFIDENCE_THRESHOLD)
                    .toList();
            uses.addAll(filteredHits);
        }
        return EitherUsagesOrError.from(uses);
    }

    /** Successful resolution of usages (possibly empty). */
    record Success(Set<UsageHit> hits) implements FuzzyResult {
        public Success(Set<UsageHit> hits) {
            this.hits = Set.copyOf(hits);
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
    record Ambiguous(String shortName, Set<CodeUnit> candidateTargets, Set<UsageHit> hits) implements FuzzyResult {
        public Ambiguous(String shortName, Set<CodeUnit> candidateTargets, Set<UsageHit> hits) {
            this.shortName = shortName;
            this.candidateTargets = Set.copyOf(candidateTargets);
            this.hits = Set.copyOf(hits);
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
            this.shortName = shortName;
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
