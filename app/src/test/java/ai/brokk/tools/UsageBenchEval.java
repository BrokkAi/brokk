package ai.brokk.tools;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.usages.FuzzyUsageFinder;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.project.IProject;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
        if (!Files.exists(inputDir) || !Files.isDirectory(inputDir)) {
            System.err.println("Error: --input-dir does not exist or is not a directory: " + inputDir);
            return 1;
        }

        List<ProjectEntry> projectEntries = discoverProjects();
        printStartupBanner(projectEntries.size());

        List<ProjectResult> results = new ArrayList<>();
        for (ProjectEntry entry : projectEntries) {
            String projectName = entry.projectDir().getFileName().toString();
            System.out.printf("Evaluating project: %s (%s)...%n", projectName, entry.language().internalName());
            try (SimpleProject project = (SimpleProject) loadProject(entry)) {
                ProgramUsages groundTruth = loadGroundTruth(entry.usagesJsonPath());
                ProjectResult result = evaluateProject(project, groundTruth, entry.language());
                results.add(result);
                System.out.printf(
                        "  TP=%d, FP=%d, FN=%d, P=%.3f, R=%.3f, F1=%.3f%n",
                        result.truePositives(),
                        result.falsePositives(),
                        result.falseNegatives(),
                        result.precision(),
                        result.recall(),
                        result.f1());
            } catch (Exception e) {
                System.err.printf("Failed to process %s: %s%n", entry.projectDir(), e.getMessage());
            }
        }

        AggregateMetrics aggregate = computeAggregateMetrics(results);
        EvalResults evalResults = new EvalResults(results, aggregate);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evalResults);
        Files.writeString(output, json);

        printSummary(aggregate);
        return 0;
    }

    private void printStartupBanner(int projectCount) {
        System.out.println("================================================================================");
        System.out.println(" UsageBenchEval - FuzzyUsageFinder Benchmark");
        System.out.println("================================================================================");
        System.out.printf(" Input Directory: %s%n", inputDir.toAbsolutePath());
        System.out.printf(" Language Filter: %s%n", language);
        System.out.printf(" Output File:     %s%n", output.toAbsolutePath());
        System.out.printf(" Projects Found:  %d%n", projectCount);
        if (!projects.isEmpty()) {
            System.out.printf(" Targeted:        %s%n", String.join(", ", projects));
        }
        System.out.println("--------------------------------------------------------------------------------");
    }

    private AggregateMetrics computeAggregateMetrics(List<ProjectResult> results) {
        int totalTP = results.stream().mapToInt(ProjectResult::truePositives).sum();
        int totalFP = results.stream().mapToInt(ProjectResult::falsePositives).sum();
        int totalFN = results.stream().mapToInt(ProjectResult::falseNegatives).sum();

        double p = calculatePrecision(totalTP, totalFP);
        double r = calculateRecall(totalTP, totalFN);
        double f1 = calculateF1(p, r);

        return new AggregateMetrics(totalTP, totalFP, totalFN, p, r, f1);
    }

    private void printSummary(AggregateMetrics aggregate) {
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println(" Summary Results");
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf(" Total TP: %d%n", aggregate.totalTP());
        System.out.printf(" Total FP: %d%n", aggregate.totalFP());
        System.out.printf(" Total FN: %d%n", aggregate.totalFN());
        System.out.printf(" Precision: %.3f%n", aggregate.precision());
        System.out.printf(" Recall:    %.3f%n", aggregate.recall());
        System.out.printf(" F1 Score:  %.3f%n", aggregate.f1());
        System.out.println("================================================================================");
        System.out.printf(" Results written to: %s%n", output.toAbsolutePath());
    }

    private List<ProjectEntry> discoverProjects() throws IOException {
        List<ProjectEntry> entries = new ArrayList<>();
        if (!Files.exists(inputDir) || !Files.isDirectory(inputDir)) {
            return entries;
        }

        try (Stream<Path> langDirs = Files.list(inputDir)) {
            langDirs.filter(Files::isDirectory).forEach(langDir -> {
                String langName = langDir.getFileName().toString().toUpperCase(Locale.ROOT);
                if (!"ALL".equals(language) && !langName.equals(language)) {
                    return;
                }

                Language lang;
                try {
                    lang = Languages.valueOf(langName);
                } catch (IllegalArgumentException e) {
                    return;
                }

                try (Stream<Path> projectPaths = Files.list(langDir)) {
                    projectPaths.filter(Files::isDirectory).forEach(projectDir -> {
                        String projectName = projectDir.getFileName().toString();
                        if (projectName.endsWith("-usages.json")) {
                            return;
                        }

                        if (!projects.isEmpty() && !projects.contains(projectName)) {
                            return;
                        }

                        Path usagesJson = langDir.resolve(projectName + "-usages.json");
                        if (Files.exists(usagesJson)) {
                            entries.add(new ProjectEntry(projectDir, usagesJson, lang));
                        }
                    });
                } catch (IOException e) {
                    System.err.println("Error scanning language directory: " + langDir);
                }
            });
        }
        return entries;
    }

    private IProject loadProject(ProjectEntry entry) throws IOException {
        Set<String> extensions = entry.language().getExtensions();
        try (Stream<Path> walk = Files.walk(entry.projectDir())) {
            List<ProjectFile> projectFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> extensions.stream().anyMatch(ext -> p.toString().endsWith("." + ext)))
                    .map(p -> new ProjectFile(entry.projectDir(), entry.projectDir().relativize(p)))
                    .toList();

            return new SimpleProject(entry.projectDir(), entry.language(), projectFiles);
        }
    }

    private ProgramUsages loadGroundTruth(Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(path.toFile(), ProgramUsages.class);
    }

    private ProjectResult evaluateProject(SimpleProject project, ProgramUsages groundTruth, Language language)
            throws InterruptedException {
        IAnalyzer analyzer = language.createAnalyzer(project);
        FuzzyUsageFinder finder = new FuzzyUsageFinder(project, analyzer, null, null);

        int totalTP = 0;
        int totalFP = 0;
        int totalFN = 0;

        for (CodeUnitUsages unit : groundTruth.codeUnits()) {
            var result = finder.findUsages(unit.fullyQualifiedName());
            var either = result.toEither();

            Set<String> expectedFqns = unit.usages().stream()
                    .map(UsageLocation::fullyQualifiedName)
                    .collect(Collectors.toSet());

            Set<String> detectedFqns = new HashSet<>();
            if (either.hasUsages()) {
                detectedFqns = either.getUsages().stream()
                        .map(hit -> hit.enclosing().fqName())
                        .collect(Collectors.toSet());
            } else if (either.hasErrorMessage()) {
                System.err.printf("  Warning: Finder failed for %s: %s%n", unit.fullyQualifiedName(), either.getErrorMessage());
            }

            Set<String> tp = new HashSet<>(detectedFqns);
            tp.retainAll(expectedFqns);

            Set<String> fp = new HashSet<>(detectedFqns);
            fp.removeAll(expectedFqns);

            Set<String> fn = new HashSet<>(expectedFqns);
            fn.removeAll(detectedFqns);

            totalTP += tp.size();
            totalFP += fp.size();
            totalFN += fn.size();
        }

        double precision = calculatePrecision(totalTP, totalFP);
        double recall = calculateRecall(totalTP, totalFN);
        double f1 = calculateF1(precision, recall);

        return new ProjectResult(
                project.getRoot().getFileName().toString(),
                language.internalName(),
                totalTP,
                totalFP,
                totalFN,
                precision,
                recall,
                f1);
    }

    private double calculatePrecision(int tp, int fp) {
        if (tp + fp == 0) return 1.0;
        return (double) tp / (tp + fp);
    }

    private double calculateRecall(int tp, int fn) {
        if (tp + fn == 0) return 1.0;
        return (double) tp / (tp + fn);
    }

    private double calculateF1(double p, double r) {
        if (p + r == 0) return 0.0;
        return 2 * (p * r) / (p + r);
    }

    private record ProjectEntry(Path projectDir, Path usagesJsonPath, Language language) {}

    private static class SimpleProject implements IProject {
        private final Path root;
        private final Language language;
        private final Set<ProjectFile> files;

        SimpleProject(Path root, Language language, List<ProjectFile> files) {
            this.root = root;
            this.language = language;
            this.files = Set.copyOf(files);
        }

        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public Set<Language> getAnalyzerLanguages() {
            return Set.of(language);
        }

        @Override
        public Set<ProjectFile> getAllFiles() {
            return files;
        }

        @Override
        public void close() {
            // No-op
        }
    }

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
