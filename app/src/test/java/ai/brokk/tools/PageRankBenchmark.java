package ai.brokk.tools;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitDistance;
import ai.brokk.git.GitRepo;
import ai.brokk.git.IGitRepo;
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

    @CommandLine.Option(names = {"-n", "--nodes", "--files"}, description = "Number of nodes (files) in the synthetic project (default: 500)")
    private int nodeCount = 500;

    @CommandLine.Option(names = "--seed", description = "Random seed for generation")
    private @Nullable Long seed;

    @CommandLine.Option(names = "--seed-count", description = "Number of seed files for ranking (default: 3)")
    private int seedCount = 3;

    @CommandLine.Option(names = "--topk", description = "Number of related files to return (default: 20)")
    private int topK = 20;

    @CommandLine.Option(names = "--reversed", description = "Whether to use reversed ranking logic")
    private boolean reversed = false;

    @CommandLine.Option(names = "--scenario", description = "Scenario to run: sparse, normal, dense, all (default: all)")
    private String scenario = "all";

    private static final double SPARSE_EDGE_FRACTION = 0.25;
    private static final double NORMAL_EDGE_FRACTION = 0.50;
    private static final double DENSE_EDGE_FRACTION = 0.75;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new PageRankBenchmark()).execute(args);
        System.exit(exitCode);
    }

    private record ScenarioConfig(
            String name,
            double edgeFraction) {

        double importProb(int nodes) {
            // We want the average degree to be proportional to edgeFraction * log(nodes) or similar.
            // Simplified: edgeFraction / 10.0 provides a reasonable spread for benchmarking.
            return (edgeFraction * 5.0) / nodes;
        }

        double commitDensity() {
            return 2.0 + (edgeFraction * 10.0);
        }

        int commitCount(int nodes) {
            return (int) (nodes * edgeFraction);
        }
    }

    private record IterationResult(long analyzerNanos, long importNanos, long gitNanos) {}

    @Override
    public Integer call() throws Exception {
        long baseSeed = seed != null ? seed : System.currentTimeMillis();
        printStartupBanner(baseSeed);

        List<ScenarioConfig> scenarios = new ArrayList<>();
        boolean all = "all".equalsIgnoreCase(scenario);
        if (all || "sparse".equalsIgnoreCase(scenario)) {
            scenarios.add(new ScenarioConfig("sparse", SPARSE_EDGE_FRACTION));
        }
        if (all || "normal".equalsIgnoreCase(scenario)) {
            scenarios.add(new ScenarioConfig("normal", NORMAL_EDGE_FRACTION));
        }
        if (all || "dense".equalsIgnoreCase(scenario)) {
            scenarios.add(new ScenarioConfig("dense", DENSE_EDGE_FRACTION));
        }

        for (ScenarioConfig config : scenarios) {
            runScenario(config, baseSeed);
        }

        return 0;
    }

    private void runScenario(ScenarioConfig config, long baseSeed) throws Exception {
        double importProb = config.importProb(nodeCount);
        double commitDensity = config.commitDensity();
        int commitCount = config.commitCount(nodeCount);

        System.out.println("=".repeat(60));
        System.out.printf(Locale.ROOT, "SCENARIO: %s (Import Prob: %.4f, Commit Density: %.1f, Commits: %d)%n",
                config.name().toUpperCase(Locale.ROOT), importProb, commitDensity, commitCount);
        System.out.println("=".repeat(60));

        long scenarioSeed = baseSeed ^ config.name().hashCode();
        Random random = new Random(scenarioSeed);

        List<String> fileNames = IntStream.range(0, nodeCount)
                .mapToObj(i -> String.format("File%05d", i))
                .toList();

        String firstFile = String.format("File%05d.java", 0);
        var builder = InlineTestProjectCreator.code(
                generateFileContent(0, fileNames, random, importProb),
                firstFile).withGit();

        for (int i = 1; i < nodeCount; i++) {
            builder.addFileContents(
                    generateFileContent(i, fileNames, random, importProb),
                    String.format("File%05d.java", i));
        }

        for (int i = 0; i < commitCount; i++) {
            int filesInCommit = (int) Math.max(2, Math.round(commitDensity + random.nextGaussian()));
            List<String> commitFiles = random.ints(0, nodeCount)
                    .distinct()
                    .limit(filesInCommit)
                    .mapToObj(idx -> String.format("File%05d.java", idx))
                    .toList();

            for (int j = 0; j < commitFiles.size() - 1; j++) {
                builder.addCommit(commitFiles.get(j), commitFiles.get(j + 1));
            }
        }

        try (var project = builder.build()) {
            IGitRepo iRepo = project.getRepo();
            GitRepo repo = iRepo instanceof GitRepo gr ? gr : null;

            if (repo == null) {
                System.out.println("  [Warning] Project does not have a valid GitRepo. Skipping GitDistance.");
            }

            List<ProjectFile> allFiles = project.getAllFiles().stream().sorted().toList();

            Map<ProjectFile, Double> seedWeights = new HashMap<>();
            random.ints(0, allFiles.size())
                    .distinct()
                    .limit(seedCount)
                    .forEach(idx -> seedWeights.put(allFiles.get(idx), 1.0));

            if (warmUpIterations > 0) {
                System.out.printf(Locale.ROOT, "Warming up (%d iterations)...%n", warmUpIterations);
                for (int i = 0; i < warmUpIterations; i++) {
                    IAnalyzer analyzer = Languages.JAVA.createAnalyzer(project);
                    ImportPageRanker.getRelatedFilesByImports(analyzer, seedWeights, topK, reversed);
                    if (repo != null) {
                        GitDistance.getRelatedFiles(repo, seedWeights, topK, reversed);
                    }
                }
            }

            System.out.printf(Locale.ROOT, "Measuring (%d iterations)...%n", iterations);
            List<IterationResult> results = new ArrayList<>();
            for (int i = 0; i < iterations; i++) {
                long t0 = System.nanoTime();
                IAnalyzer analyzer = Languages.JAVA.createAnalyzer(project);
                long t1 = System.nanoTime();

                ImportPageRanker.getRelatedFilesByImports(analyzer, seedWeights, topK, reversed);
                long t2 = System.nanoTime();

                if (repo != null) {
                    GitDistance.getRelatedFiles(repo, seedWeights, topK, reversed);
                }
                long t3 = System.nanoTime();

                IterationResult res = new IterationResult(t1 - t0, t2 - t1, repo != null ? t3 - t2 : 0L);
                results.add(res);

                System.out.printf(Locale.ROOT, "  Iteration %d: Analyzer=%s, ImportRanker=%s, GitDistance=%s%n",
                        i + 1, formatDuration(res.analyzerNanos()), formatDuration(res.importNanos()), formatDuration(res.gitNanos()));
            }

            printSummary(results);
        }
    }

    private void printStartupBanner(long seed) {
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("PageRank Benchmark Tool");
        System.out.printf(Locale.ROOT, "Seed: %d | Nodes: %d | SeedCount: %d | TopK: %d | Reversed: %b%n",
                seed, nodeCount, seedCount, topK, reversed);
        System.out.println("--------------------------------------------------------------------------------");
    }

    private void printSummary(List<IterationResult> results) {
        long analyzerMin = results.stream().mapToLong(IterationResult::analyzerNanos).min().orElse(0);
        long analyzerMax = results.stream().mapToLong(IterationResult::analyzerNanos).max().orElse(0);
        double analyzerMean = results.stream().mapToLong(IterationResult::analyzerNanos).average().orElse(0);

        long importMin = results.stream().mapToLong(IterationResult::importNanos).min().orElse(0);
        long importMax = results.stream().mapToLong(IterationResult::importNanos).max().orElse(0);
        double importMean = results.stream().mapToLong(IterationResult::importNanos).average().orElse(0);

        long gitMin = results.stream().mapToLong(IterationResult::gitNanos).min().orElse(0);
        long gitMax = results.stream().mapToLong(IterationResult::gitNanos).max().orElse(0);
        double gitMean = results.stream().mapToLong(IterationResult::gitNanos).average().orElse(0);

        System.out.println("\nScenario Statistics:");
        System.out.printf(Locale.ROOT, "  Analyzer (Create): [Min: %s, Mean: %s, Max: %s]%n",
                formatDuration(analyzerMin), formatDuration((long) analyzerMean), formatDuration(analyzerMax));
        System.out.printf(Locale.ROOT, "  ImportPageRanker:  [Min: %s, Mean: %s, Max: %s]%n",
                formatDuration(importMin), formatDuration((long) importMean), formatDuration(importMax));
        System.out.printf(Locale.ROOT, "  GitDistance:       [Min: %s, Mean: %s, Max: %s]%n",
                formatDuration(gitMin), formatDuration((long) gitMean), formatDuration(gitMax));
        System.out.println();
    }

    static String generateFileContent(int index, List<String> allFileNames, Random random, double edgeProb) {
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
