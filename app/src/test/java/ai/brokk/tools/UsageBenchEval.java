package ai.brokk.tools;

import ai.brokk.*;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.usages.FuzzyUsageFinder;
import ai.brokk.analyzer.usages.UsageHit;
import ai.brokk.concurrent.ExecutorsUtil;
import ai.brokk.concurrent.LoggingExecutorService;
import ai.brokk.project.IProject;
import ai.brokk.project.ModelProperties;
import ai.brokk.tools.UsageBenchTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
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
            defaultValue = "../build/reports/usage-results",
            description = "Output directory for results (default: <project-root>/build/reports/usage-results)")
    private Path output = Path.of("../build/reports/usage-results");

    @CommandLine.Option(
            names = {"--projects"},
            description = "Specific project names to target (repeatable)")
    private List<String> projects = List.of();

    @CommandLine.Option(
            names = {"-p", "--parallelism"},
            defaultValue = "2",
            description = "Number of projects to evaluate in parallel (default: 2)")
    private int parallelism = 2;

    @CommandLine.Option(
            names = {"--online"},
            description = "Use online Service instead of OfflineService")
    private boolean online = false;

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

        List<ProjectResult> projectResults = new ArrayList<>();
        boolean hadFailures;
        List<ProjectEvalResult> evalResultsList;

        try (LoggingExecutorService executor = ExecutorsUtil.newFixedThreadExecutor(parallelism, "usage-bench")) {
            List<CompletableFuture<ProjectEvalResult>> futures = projectEntries.stream()
                    .map(entry -> executor.submit(new ProjectEvalCallable(entry)))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            evalResultsList = futures.stream().map(CompletableFuture::join).toList();

            hadFailures = evalResultsList.stream().anyMatch(r -> !r.success());

            for (ProjectEvalResult evalResult : evalResultsList) {
                if (!evalResult.success()) {
                    System.err.printf(
                            "Failed to process %s: %s%n", evalResult.entry().projectDir(), evalResult.errorMessage());
                    continue;
                }

                ProjectResult result = evalResult.result();
                projectResults.add(result);

                System.out.printf(
                        "[%s] TP=%d, FP=%d, FN=%d, P=%.3f, R=%.3f, F1=%.3f%n",
                        result.project(),
                        result.truePositives(),
                        result.falsePositives(),
                        result.falseNegatives(),
                        result.precision(),
                        result.recall(),
                        result.f1());
            }
            executor.shutdownAndAwait(60_000, "usage-bench");
        }

        Files.createDirectories(output);
        AggregateMetrics aggregate = computeAggregateMetrics(projectResults);
        EvalResults evalResults = new EvalResults(projectResults, aggregate);

        // Collect failed projects
        List<FailedProject> failedProjects = evalResultsList.stream()
                .filter(r -> !r.success())
                .map(r -> new FailedProject(r.entry().projectDir().getFileName().toString(), r.errorMessage()))
                .toList();

        AggregateSummary aggregateSummary = new AggregateSummary(online, aggregate, projectResults, failedProjects);

        var writer = new ObjectMapper().writerWithDefaultPrettyPrinter();

        // Write backwards-compatible summary.json
        Files.writeString(
                output.resolve("summary.json"),
                writer.writeValueAsString(evalResults),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        // Write new aggregate-summary.json
        Files.writeString(
                output.resolve("aggregate-summary.json"),
                writer.writeValueAsString(aggregateSummary),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        printSummary(aggregate);

        if (hadFailures || projectResults.isEmpty()) {
            if (projectResults.isEmpty()) {
                System.err.println("Error: No projects were successfully evaluated.");
            }
            return 1;
        }
        return 0;
    }

    private void printStartupBanner(int projectCount) {
        System.out.println("================================================================================");
        System.out.println(" UsageBenchEval - FuzzyUsageFinder Benchmark");
        System.out.println("================================================================================");
        System.out.printf(" Input Directory: %s%n", inputDir.toAbsolutePath());
        System.out.printf(" Language Filter: %s%n", language);
        System.out.printf(" Parallelism:     %d%n", parallelism);
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
        System.out.printf(" Results written to directory: %s%n", output.toAbsolutePath());
        System.out.println("  - summary.json");
        System.out.println("  - true-positives.json");
        System.out.println("  - false-positives.json");
        System.out.println("  - false-negatives.json");
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
                    System.err.println("Error scanning language directory " + langDir + ": " + e.getMessage());
                }
            });
        }
        return entries;
    }

    private IProject loadProject(ProjectEntry entry) throws IOException {
        Set<String> extensions = entry.language().getExtensions();
        try (Stream<Path> walk = Files.walk(entry.projectDir())) {
            List<ProjectFile> projectFiles = walk.filter(Files::isRegularFile)
                    .filter(p ->
                            extensions.stream().anyMatch(ext -> p.toString().endsWith("." + ext)))
                    .map(p -> new ProjectFile(
                            entry.projectDir(), entry.projectDir().relativize(p)))
                    .toList();

            return new SimpleProject(entry.projectDir(), entry.language(), projectFiles);
        }
    }

    private ProgramUsages loadGroundTruth(Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(path.toFile(), ProgramUsages.class);
    }

    private EvaluationData evaluateProject(
            IProject project, ProgramUsages groundTruth, Language language, boolean online)
            throws InterruptedException {
        IAnalyzer analyzer = language.createAnalyzer(project);
        AbstractService service = online ? new Service(project) : new OfflineService(project);
        var cm = new ContextManager(project);
        var model = service.getModel(ModelProperties.ModelType.USAGES);
        var llm = online
                ? new Llm(
                        model,
                        "Disambiguate Code Unit Usages",
                        TaskResult.Type.CLASSIFY,
                        cm,
                        false,
                        false,
                        false,
                        false)
                : null;
        FuzzyUsageFinder finder = new FuzzyUsageFinder(project, analyzer, service, llm);

        String projectName = project.getRoot().getFileName().toString();
        String projectPath = project.getRoot().toAbsolutePath().toString();
        List<CodeUnitDetail> projectTPs = new ArrayList<>();
        List<CodeUnitDetail> projectFPs = new ArrayList<>();
        List<CodeUnitDetail> projectFNs = new ArrayList<>();

        int totalTP = 0;
        int totalFP = 0;
        int totalFN = 0;

        for (CodeUnitUsages unit : groundTruth.codeUnits()) {
            // Get the declaration file path for the searched symbol
            var definitions = analyzer.getDefinitions(unit.fullyQualifiedName());
            String searchedFilePath = "";
            if (!definitions.isEmpty()) {
                var def = definitions.iterator().next();
                searchedFilePath = def.source().absPath().toString();
            }

            var result = finder.findUsages(unit.fullyQualifiedName());
            var either = result.toEither();

            Map<String, UsageLocation> expectedLocations = unit.usages().stream()
                    .collect(Collectors.toMap(UsageLocation::fullyQualifiedName, loc -> loc, (a, b) -> a));

            Set<String> expectedFqns = expectedLocations.keySet();

            Set<UsageHit> detectedHits = new HashSet<>();
            Set<String> detectedFqns = new HashSet<>();
            if (either.hasUsages()) {
                detectedHits = either.getUsages();
                detectedFqns = detectedHits.stream()
                        .map(hit -> hit.enclosing().fqName())
                        .collect(Collectors.toSet());
            } else if (either.hasErrorMessage()) {
                System.err.printf(
                        "  Warning: Finder failed for %s: %s%n", unit.fullyQualifiedName(), either.getErrorMessage());
            }

            Set<String> tp = new HashSet<>(detectedFqns);
            tp.retainAll(expectedFqns);

            Set<String> fp = new HashSet<>(detectedFqns);
            fp.removeAll(expectedFqns);

            Set<String> fn = new HashSet<>(expectedFqns);
            fn.removeAll(detectedFqns);

            Map<String, UsageHit> fqnToHit = detectedHits.stream()
                    .collect(Collectors.toMap(
                            hit -> hit.enclosing().fqName(), hit -> hit, (a, b) -> a // keep first if duplicate
                            ));

            if (!tp.isEmpty()) {
                List<UsageDetail> tpDetails = tp.stream()
                        .map(fqn -> {
                            UsageHit hit = fqnToHit.get(fqn);
                            return new UsageDetail(
                                    fqn,
                                    hit != null ? hit.snippet() : "",
                                    hit != null ? hit.file().absPath().toString() : "",
                                    hit != null ? hit.file().getSyntaxStyle() : "");
                        })
                        .toList();
                projectTPs.add(new CodeUnitDetail(
                        unit.fullyQualifiedName(),
                        searchedFilePath,
                        projectName,
                        projectPath,
                        language.internalName(),
                        tpDetails));
            }
            if (!fp.isEmpty()) {
                List<UsageDetail> fpDetails = fp.stream()
                        .map(fqn -> {
                            UsageHit hit = fqnToHit.get(fqn);
                            return new UsageDetail(
                                    fqn,
                                    hit != null ? hit.snippet() : "",
                                    hit != null ? hit.file().absPath().toString() : "",
                                    hit != null ? hit.file().getSyntaxStyle() : "");
                        })
                        .toList();
                projectFPs.add(new CodeUnitDetail(
                        unit.fullyQualifiedName(),
                        searchedFilePath,
                        projectName,
                        projectPath,
                        language.internalName(),
                        fpDetails));
            }
            if (!fn.isEmpty()) {
                List<UsageDetail> fnDetails = fn.stream()
                        .map(fqn -> {
                            UsageLocation loc = expectedLocations.get(fqn);
                            String snippet = (loc != null && loc.snippet() != null) ? loc.snippet() : "";
                            return new UsageDetail(fqn, snippet, "", "");
                        })
                        .toList();
                projectFNs.add(new CodeUnitDetail(
                        unit.fullyQualifiedName(),
                        searchedFilePath,
                        projectName,
                        projectPath,
                        language.internalName(),
                        fnDetails));
            }

            totalTP += tp.size();
            totalFP += fp.size();
            totalFN += fn.size();
        }

        double precision = calculatePrecision(totalTP, totalFP);
        double recall = calculateRecall(totalTP, totalFN);
        double f1 = calculateF1(precision, recall);

        ProjectResult result = new ProjectResult(
                projectName, language.internalName(), totalTP, totalFP, totalFN, precision, recall, f1);

        return new EvaluationData(result, projectTPs, projectFPs, projectFNs);
    }

    private double calculatePrecision(int tp, int fp) {
        if (tp + fp == 0) return 0.0;
        return (double) tp / (tp + fp);
    }

    private double calculateRecall(int tp, int fn) {
        if (tp + fn == 0) return 0.0;
        return (double) tp / (tp + fn);
    }

    private double calculateF1(double p, double r) {
        if (p + r == 0) return 0.0;
        return 2 * (p * r) / (p + r);
    }

    private void writeProjectResults(
            Path projectOutputDir,
            ProjectResult result,
            List<CodeUnitDetail> tpDetails,
            List<CodeUnitDetail> fpDetails,
            List<CodeUnitDetail> fnDetails)
            throws IOException {
        Files.createDirectories(projectOutputDir);
        var writer = new ObjectMapper().writerWithDefaultPrettyPrinter();

        Files.writeString(
                projectOutputDir.resolve("summary.json"),
                writer.writeValueAsString(result),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(
                projectOutputDir.resolve("true-positives.json"),
                writer.writeValueAsString(new DetailedResults(tpDetails)),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(
                projectOutputDir.resolve("false-positives.json"),
                writer.writeValueAsString(new DetailedResults(fpDetails)),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(
                projectOutputDir.resolve("false-negatives.json"),
                writer.writeValueAsString(new DetailedResults(fnDetails)),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private record ProjectEntry(Path projectDir, Path usagesJsonPath, Language language) {}

    private record ProjectEvalResult(
            ProjectEntry entry,
            @Nullable ProjectResult result,
            boolean success,
            String errorMessage,
            List<CodeUnitDetail> tpDetails,
            List<CodeUnitDetail> fpDetails,
            List<CodeUnitDetail> fnDetails) {

        static ProjectEvalResult failure(ProjectEntry entry, String errorMessage) {
            return new ProjectEvalResult(entry, null, false, errorMessage, List.of(), List.of(), List.of());
        }

        static ProjectEvalResult success(
                ProjectEntry entry,
                ProjectResult result,
                List<CodeUnitDetail> tpDetails,
                List<CodeUnitDetail> fpDetails,
                List<CodeUnitDetail> fnDetails) {
            return new ProjectEvalResult(entry, result, true, "", tpDetails, fpDetails, fnDetails);
        }
    }

    private class ProjectEvalCallable implements Callable<ProjectEvalResult> {
        private final ProjectEntry entry;

        ProjectEvalCallable(ProjectEntry entry) {
            this.entry = entry;
        }

        @Override
        public ProjectEvalResult call() {
            String projectName = entry.projectDir().getFileName().toString();
            System.out.printf(
                    "Evaluating project: %s (%s)...%n",
                    projectName, entry.language().internalName());

            try (IProject project = loadProject(entry)) {
                ProgramUsages groundTruth = loadGroundTruth(entry.usagesJsonPath());
                EvaluationData evalData = evaluateProject(project, groundTruth, entry.language(), online);

                Path projectOutputDir =
                        output.resolve(entry.language().internalName()).resolve(projectName);

                writeProjectResults(
                        projectOutputDir,
                        evalData.projectResult(),
                        evalData.tpDetails(),
                        evalData.fpDetails(),
                        evalData.fnDetails());

                return ProjectEvalResult.success(
                        entry,
                        evalData.projectResult(),
                        evalData.tpDetails(),
                        evalData.fpDetails(),
                        evalData.fnDetails());
            } catch (Exception e) {
                return ProjectEvalResult.failure(entry, e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }
    }

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

    private record EvaluationData(
            ProjectResult projectResult,
            List<CodeUnitDetail> tpDetails,
            List<CodeUnitDetail> fpDetails,
            List<CodeUnitDetail> fnDetails) {}
}
