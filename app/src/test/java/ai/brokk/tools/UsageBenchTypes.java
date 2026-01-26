package ai.brokk.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * Shared data types for UsageBenchEval and UsageResultsExplorer.
 */
@NullMarked
public class UsageBenchTypes {

    // --- JSON Input Records (matching Scala domain) ---

    public record ProgramUsages(@JsonProperty("codeUnits") List<CodeUnitUsages> codeUnits) {}

    public record CodeUnitUsages(
            @JsonProperty("fullyQualifiedName") String fullyQualifiedName,
            @JsonProperty("type") String type,
            @JsonProperty("usages") List<UsageLocation> usages) {}

    public record UsageLocation(
            @JsonProperty("fullyQualifiedName") String fullyQualifiedName,
            @JsonProperty("lineNumber") int lineNumber) {}

    // --- Result Records ---

    public record EvalResults(
            @JsonProperty("projects") List<ProjectResult> projects,
            @JsonProperty("aggregate") AggregateMetrics aggregate) {}

    public record ProjectResult(
            @JsonProperty("project") String project,
            @JsonProperty("language") String language,
            @JsonProperty("truePositives") int truePositives,
            @JsonProperty("falsePositives") int falsePositives,
            @JsonProperty("falseNegatives") int falseNegatives,
            @JsonProperty("precision") double precision,
            @JsonProperty("recall") double recall,
            @JsonProperty("f1") double f1) {}

    public record AggregateMetrics(
            @JsonProperty("totalTP") int totalTP,
            @JsonProperty("totalFP") int totalFP,
            @JsonProperty("totalFN") int totalFN,
            @JsonProperty("precision") double precision,
            @JsonProperty("recall") double recall,
            @JsonProperty("f1") double f1) {}

    public record UsageDetail(
            @JsonProperty("fqName") String fqName,
            @JsonProperty("snippet") String snippet,
            @JsonProperty("filePath") String filePath,
            @JsonProperty("syntaxStyle") String syntaxStyle) {}

    public record CodeUnitDetail(
            @JsonProperty("searchedFqn") String searchedFqn,
            @JsonProperty("searchedFilePath") String searchedFilePath,
            @JsonProperty("project") String project,
            @JsonProperty("projectPath") String projectPath,
            @JsonProperty("language") String language,
            @JsonProperty("usages") List<UsageDetail> usages) {}

    public record DetailedResults(@JsonProperty("codeUnits") List<CodeUnitDetail> codeUnits) {}
}
