package ai.brokk.tools;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * DTOs for the RM-shading evaluation harness.
 */
public final class RmShadingEvalTypes {

    private RmShadingEvalTypes() {}

    /** One commit range to evaluate (fromRef..toRef]. */
    public record RmShadingDatasetEntry(
            @JsonProperty("id") String id,
            @JsonProperty("fromRef") String fromRef,
            @JsonProperty("toRef") String toRef) {

        @JsonCreator
        public RmShadingDatasetEntry {
            if (id == null) id = "";
            if (fromRef == null) fromRef = "";
            if (toRef == null) toRef = "HEAD";
        }
    }

    /** Root of the dataset JSON file: { "entries": [ ... ] } */
    public record RmShadingDataset(@JsonProperty("entries") List<RmShadingDatasetEntry> entries) {

        @JsonCreator
        public RmShadingDataset {
            entries = entries != null ? entries : List.of();
        }
    }
}
