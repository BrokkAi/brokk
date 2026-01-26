package ai.brokk.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import picocli.CommandLine;

@NullMarked
@CommandLine.Command(
        name = "UsageBenchEval",
        mixinStandardHelpOptions = true,
        description = "Evaluates FuzzyUsageFinder against a labeled dataset")
public class UsageBenchEval implements Callable<Integer> {

    @CommandLine.Option(
            names = {"--input-dir"},
            required = true,
            description = "Path to dataset root directory")
    private Path inputDir = Path.of(".");

    @CommandLine.Option(
            names = {"--language"},
            defaultValue = "ALL",
            description = "Filter by Language.internalName (default: ALL)")
    private String language = "ALL";

    @CommandLine.Option(
            names = {"--output"},
            defaultValue = "./usage-results.json",
            description = "Output file path for results (default: ./usage-results.json)")
    private Path output = Path.of("./usage-results.json");

    @CommandLine.Option(
            names = {"--projects"},
            description = "Specific project names to target (repeatable)")
    private List<String> projects = List.of();

    public static void main(String[] args) {
        int exitCode = new CommandLine(new UsageBenchEval()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        // Implementation stub
        return 0;
    }

    // --- JSON Input Records (matching Scala domain) ---

    public record ProgramUsages(List<CodeUnitUsages> codeUnits) {}

    public record CodeUnitUsages(String fullyQualifiedName, String type, List<UsageLocation> usages) {}

    public record UsageLocation(String fullyQualifiedName, int lineNumber) {}

    // --- Result Records ---

    public record EvalResults(List<ProjectResult> projects, AggregateMetrics aggregate) {}

    public record ProjectResult(
            String project,
            String language,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            double precision,
            double recall,
            double f1) {}

    public record AggregateMetrics(
            int totalTP, int totalFP, int totalFN, double precision, double recall, double f1) {}
}
