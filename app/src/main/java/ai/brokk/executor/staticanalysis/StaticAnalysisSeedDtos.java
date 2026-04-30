package ai.brokk.executor.staticanalysis;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class StaticAnalysisSeedDtos {
    private StaticAnalysisSeedDtos() {}

    public static final String PHASE_STATIC_SEED = "static_seed";

    public record Request(
            @JsonProperty("scanId") @Nullable String scanId,
            @JsonProperty("targetSeedCount") @Nullable Integer targetSeedCount,
            @JsonProperty("maxDurationMs") @Nullable Integer maxDurationMs,
            @JsonProperty("includePreview") @Nullable Boolean includePreview) {
        public static final int DEFAULT_TARGET_SEED_COUNT = 25;
        public static final int DEFAULT_MAX_DURATION_MS = 15_000;

        public NormalizedRequest normalized() {
            return new NormalizedRequest(
                    scanId == null ? "" : scanId.strip(),
                    targetSeedCount == null ? DEFAULT_TARGET_SEED_COUNT : targetSeedCount,
                    maxDurationMs == null ? DEFAULT_MAX_DURATION_MS : maxDurationMs,
                    Boolean.TRUE.equals(includePreview));
        }
    }

    public record NormalizedRequest(String scanId, int targetSeedCount, int maxDurationMs, boolean includePreview) {}

    public record Response(
            @JsonProperty("scanId") String scanId,
            @JsonProperty("phase") String phase,
            @JsonProperty("state") String state,
            @JsonProperty("seeds") List<SeedRecord> seeds,
            @JsonProperty("events") List<Event> events) {}

    public record SeedRecord(
            @JsonProperty("file") String file,
            @JsonProperty("rank") int rank,
            @JsonProperty("selection") @Nullable Selection selection,
            @JsonProperty("suggestedAgents") List<String> suggestedAgents,
            @JsonProperty("suggestedTools") List<String> suggestedTools) {}

    public record Event(
            @JsonProperty("id") String id,
            @JsonProperty("scanId") String scanId,
            @JsonProperty("phase") String phase,
            @JsonProperty("state") String state,
            @JsonProperty("tools") List<String> tools,
            @JsonProperty("files") List<String> files,
            @JsonProperty("selection") @Nullable Selection selection,
            @JsonProperty("triggeredBy") @Nullable TriggeredBy triggeredBy,
            @JsonProperty("outcome") Outcome outcome,
            @JsonProperty("suggestedAgents") List<String> suggestedAgents) {}

    public record Selection(
            @JsonProperty("kind") String kind,
            @JsonProperty("rank") @Nullable Integer rank,
            @JsonProperty("score") @Nullable Double score,
            @JsonProperty("signals") List<Signal> signals) {}

    public static final class Signal {
        private final String kind;
        private final Map<String, Object> values;

        public Signal(@JsonProperty("kind") String kind, Map<String, Object> values) {
            this.kind = kind;
            this.values = values;
        }

        @JsonProperty("kind")
        public String kind() {
            return kind;
        }

        @JsonAnyGetter
        public Map<String, Object> values() {
            return values;
        }
    }

    public record TriggeredBy(
            @JsonProperty("phase") String phase,
            @JsonProperty("file") @Nullable String file,
            @JsonProperty("kind") String kind) {}

    public record Outcome(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message,
            @JsonProperty("findingCount") int findingCount,
            @JsonProperty("findingTypes") List<String> findingTypes) {}
}
