package ai.brokk.tools;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitDistance;
import ai.brokk.git.GitRepo;
import ai.brokk.ranking.ImportPageRanker;
import ai.brokk.testutil.InlineTestProjectCreator;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static java.util.Objects.requireNonNull;

@NullMarked
@CommandLine.Command(
        name = "PageRankBenchmark",
        mixinStandardHelpOptions = true,
        description = "Benchmarks ImportPageRanker and GitDistance ranking algorithms")
public class PageRankBenchmark implements Callable<Integer> {

    @CommandLine.Option(names = {"-wi", "--warm-up-iterations"}, description = "Number of warm-up iterations (default: 2)")
    private int warmUpIterations = 2;

    @CommandLine.Option(names = {"-i", "--iterations"}, description = "Number of measured iterations (default: 5)")
    private int iterations = 5;

    @CommandLine.Option(names = {"-f", "--files"}, description = "Number of files in the synthetic project (default: 500)")
    private int fileCount = 500;

    @CommandLine.Option(names = "--seed", description = "Random seed for generation")
    private @Nullable Long seed;

    @CommandLine.Option(names = "--seed-count", description = "Number of seed files for ranking (default: 3)")
    private int seedCount = 3;

    @CommandLine.Option(names = "--topk", description = "Number of related files to return (default: 20)")
    private int topK = 20;

    @CommandLine.Option(names = "--reversed", description = "Whether to use reversed ranking logic")
    private boolean reversed = false;

    @CommandLine.Option(names = "--sparse-import-prob", description = "Sparse scenario: probability of an import edge (default: 0.005)")
    private double sparseImportProb = 0.005;

    @CommandLine.Option(names = "--dense-import-prob", description = "Dense scenario: probability of an import edge (default: 0.05)")
    private double denseImportProb = 0.05;

    @CommandLine.Option(names = "--sparse-commit-density", description = "Sparse scenario: avg files per commit (default: 2.0)")
    private double sparseCommitDensity = 2.0;

    @CommandLine.Option(names = "--dense-commit-density", description = "Dense scenario: avg files per commit (default: 8.0)")
    private double denseCommitDensity = 8.0;

    @CommandLine.Option(names = "--sparse-commit-count", description = "Sparse scenario: number of commits (default: 50)")
    private int sparseCommitCount = 50;

    @CommandLine.Option(names = "--dense-commit-count", description = "Dense scenario: number of commits (default: 500)")
    private int denseCommitCount = 500;

    @CommandLine.Option(names = "--scenario", description = "Scenario to run: sparse, dense, both (default: both)")
    private String scenario = "both";

    public static void main(String[] args) {
        int exitCode = new CommandLine(new PageRankBenchmark()).execute(args);
        System.exit(exitCode);
    }

    private record ScenarioConfig(
            String name,
            double importProb,
            double commitDensity,
            int commitCount) {}

    private record IterationResult(long importNanos, long gitNanos) {}

    @Override
    public Integer call() throws Exception {
        long baseSeed = seed != null ? seed : System.currentTimeMillis();
        printStartupBanner(baseSeed);

        List<ScenarioConfig> scenarios = new ArrayList<>();
        if ("sparse".equalsIgnoreCase(scenario) || "both".equalsIgnoreCase(scenario)) {
            scenarios.add(new ScenarioConfig("sparse", sparseImportProb, sparseCommitDensity, sparseCommitCount));
        }
        if ("dense".equalsIgnoreCase(scenario) || "both".equalsIgnoreCase(scenario)) {
            scenarios.add(new ScenarioConfig("dense", denseImportProb, sparseCommitDensity, denseCommitCount));
        }

        for (ScenarioConfig config : scenarios) {
            runScenario(config, baseSeed);
        }

        return 0;
    }

    private void runScenario(ScenarioConfig config, long baseSeed) throws Exception {
        System.out.println("=".repeat(60));
        System.out.printf(Locale.ROOT, "SCENARIO: %s (Import Prob: %.3f, Commit Density: %.1f, Commits: %d)%n",
                config.name().toUpperCase(Locale.ROOT), config.importProb(), config.commitDensity(), config.commitCount());
        System.out.println("=".repeat(60));

        long scenarioSeed = baseSeed ^ config.name().hashCode();
        Random random = new Random(scenarioSeed);

        List<String> fileNames = IntStream.range(0, fileCount)
                .mapToObj(i -> String.format("File%05d", i))
                .toList();

        String firstFile = String.format("File%05d.java", 0);
        var builder = InlineTestProjectCreator.code(
                generateFileContent(0, fileNames, random, config.importProb()),
                firstFile).withGit();

        for (int i = 1; i < fileCount; i++) {
            builder.addFileContents(
                    generateFileContent(i, fileNames, random, config.importProb()),
                    String.format("File%05d.java", i));
        }

        for (int i = 0; i < config.commitCount(); i++) {
            String fileA = String.format("File%05d.java", random.nextInt(fileCount));
            String fileB = String.format("File%05d.java", random.nextInt(fileCount));
            builder.addCommit(fileA, fileB);
        }

        try (var project = builder.build()) {
            IAnalyzer analyzer = Languages.JAVA.createAnalyzer(project);
            GitRepo repo = (GitRepo) requireNonNull(project.getRepo());
            List<ProjectFile> allFiles = project.getAllFiles().stream().sorted().toList();

            Map<ProjectFile, Double> seedWeights = new HashMap<>();
            random.ints(0, allFiles.size())
                    .distinct()
                    .limit(seedCount)
                    .forEach(idx -> seedWeights.put(allFiles.get(idx), 1.0));

            if (warmUpIterations > 0) {
                System.out.printf(Locale.ROOT, "Warming up (%d iterations)...%n", warmUpIterations);
                for (int i = 0; i < warmUpIterations; i++) {
                    ImportPageRanker.getRelatedFilesByImports(analyzer, seedWeights, topK, reversed);
                    GitDistance.getRelatedFiles(repo, seedWeights, topK, reversed);
                }
            }

            System.out.printf(Locale.ROOT, "Measuring (%d iterations)...%n", iterations);
            List<IterationResult> results = new ArrayList<>();
            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                ImportPageRanker.getRelatedFilesByImports(analyzer, seedWeights, topK, reversed);
                long mid = System.nanoTime();
                GitDistance.getRelatedFiles(repo, seedWeights, topK, reversed);
                long end = System.nanoTime();

                IterationResult res = new IterationResult(mid - start, end - mid);
                results.add(res);

                System.out.printf(Locale.ROOT, "  Iteration %d: ImportRanker=%s, GitDistance=%s%n",
                        i + 1, formatDuration(res.importNanos()), formatDuration(res.gitNanos()));
            }

            printSummary(results);
        }
    }

    private void printStartupBanner(long seed) {
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("PageRank Benchmark Tool");
        System.out.printf(Locale.ROOT, "Seed: %d | Files: %d | SeedCount: %d | TopK: %d | Reversed: %b%n",
                seed, fileCount, seedCount, topK, reversed);
        System.out.println("--------------------------------------------------------------------------------");
    }

    private void printSummary(List<IterationResult> results) {
        long importMin = results.stream().mapToLong(IterationResult::importNanos).min().orElse(0);
        long importMax = results.stream().mapToLong(IterationResult::importNanos).max().orElse(0);
        double importMean = results.stream().mapToLong(IterationResult::importNanos).average().orElse(0);

        long gitMin = results.stream().mapToLong(IterationResult::gitNanos).min().orElse(0);
        long gitMax = results.stream().mapToLong(IterationResult::gitNanos).max().orElse(0);
        double gitMean = results.stream().mapToLong(IterationResult::gitNanos).average().orElse(0);

        System.out.println("\nScenario Statistics:");
        System.out.printf(Locale.ROOT, "  ImportPageRanker: [Min: %s, Mean: %s, Max: %s]%n",
                formatDuration(importMin), formatDuration((long) importMean), formatDuration(importMax));
        System.out.printf(Locale.ROOT, "  GitDistance:      [Min: %s, Mean: %s, Max: %s]%n",
                formatDuration(gitMin), formatDuration((long) gitMean), formatDuration(gitMax));
        System.out.println();
    }

    private String generateFileContent(int index, List<String> allFileNames, Random random, double edgeProb) {
        int pkgIdx = index % 10;
        String className = allFileNames.get(index);

        String imports = IntStream.range(0, allFileNames.size())
                .filter(i -> i != index && random.nextDouble() < edgeProb)
                .mapToObj(targetIdx -> String.format("import p%d.%s;", targetIdx % 10, allFileNames.get(targetIdx)))
                .sorted()
                .collect(java.util.stream.Collectors.joining("\n"));

        return String.join("\n",
                String.format("package p%d;", pkgIdx),
                "",
                imports,
                "",
                String.format("public class %s {", className),
                "  public void method() {}",
                "}");
    }

    private String formatDuration(long nanos) {
        Duration d = Duration.ofNanos(nanos);
        return String.format(Locale.ROOT, "%.3fms", d.toNanos() / 1_000_000.0);
    }
}
