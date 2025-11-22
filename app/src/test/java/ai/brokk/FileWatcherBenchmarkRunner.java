package ai.brokk;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone runner for file watcher benchmarks.
 * Can be executed from command line or IDE to run comprehensive benchmark suite.
 *
 * <p>Usage:
 * <pre>
 * // Run all benchmarks
 * java ai.brokk.FileWatcherBenchmarkRunner
 *
 * // Run specific benchmark
 * java ai.brokk.FileWatcherBenchmarkRunner latency-small
 *
 * // Specify output directory
 * java ai.brokk.FileWatcherBenchmarkRunner --output=./benchmark-results
 * </pre>
 */
public class FileWatcherBenchmarkRunner {

    private static final String DEFAULT_OUTPUT_DIR = "./benchmark-results";

    public static void main(String[] args) throws Exception {
        String outputDir = DEFAULT_OUTPUT_DIR;
        List<String> benchmarksToRun = new ArrayList<>();

        // Parse arguments
        for (String arg : args) {
            if (arg.startsWith("--output=")) {
                outputDir = arg.substring("--output=".length());
            } else {
                benchmarksToRun.add(arg);
            }
        }

        // Create output directory
        Path outputPath = Paths.get(outputDir);
        Files.createDirectories(outputPath);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path reportFile = outputPath.resolve("benchmark-report-" + timestamp + ".txt");

        System.out.println("Starting File Watcher Benchmark Suite");
        System.out.println("Output directory: " + outputPath.toAbsolutePath());
        System.out.println("Report file: " + reportFile.getFileName());
        System.out.println("=".repeat(80));

        try (PrintWriter writer = new PrintWriter(new FileWriter(reportFile.toFile()))) {
            FileWatcherBenchmarkRunner runner = new FileWatcherBenchmarkRunner(writer);

            if (benchmarksToRun.isEmpty()) {
                runner.runAllBenchmarks();
            } else {
                for (String benchmark : benchmarksToRun) {
                    runner.runBenchmark(benchmark);
                }
            }
        }

        System.out.println("\nBenchmark suite completed!");
        System.out.println("Report saved to: " + reportFile.toAbsolutePath());
    }

    private final PrintWriter writer;
    private final FileWatcherBenchmark benchmark;
    private final TestProjectGenerator generator;
    private Path tempDir;

    public FileWatcherBenchmarkRunner(PrintWriter writer) {
        this.writer = writer;
        this.benchmark = new FileWatcherBenchmark();
        this.generator = new TestProjectGenerator();
    }

    private void runAllBenchmarks() throws Exception {
        tempDir = Files.createTempDirectory("filewatcher_benchmark_");
        System.out.println("Temporary directory: " + tempDir);
        writeLine("File Watcher Benchmark Report");
        writeLine("Generated: " + LocalDateTime.now());
        writeLine("Temporary directory: " + tempDir);
        writeLine("=".repeat(80));
        writeLine("");

        try {
            runBenchmark("latency-small");
            runBenchmark("latency-medium");
            runBenchmark("bulk-small");
            runBenchmark("bulk-medium");
            runBenchmark("idle-small");
            runBenchmark("idle-medium");
        } finally {
            cleanup();
        }
    }

    private void runBenchmark(String benchmarkName) throws Exception {
        if (tempDir == null) {
            tempDir = Files.createTempDirectory("filewatcher_benchmark_");
        }

        System.out.println("\n" + "=".repeat(80));
        System.out.println("Running benchmark: " + benchmarkName);
        System.out.println("=".repeat(80));

        writeLine("");
        writeLine("=".repeat(80));
        writeLine("Benchmark: " + benchmarkName);
        writeLine("=".repeat(80));

        switch (benchmarkName) {
            case "latency-small" -> runLatencySmall();
            case "latency-medium" -> runLatencyMedium();
            case "latency-large" -> runLatencyLarge();
            case "bulk-small" -> runBulkChangeSmall();
            case "bulk-medium" -> runBulkChangeMedium();
            case "bulk-large" -> runBulkChangeLarge();
            case "idle-small" -> runIdleSmall();
            case "idle-medium" -> runIdleMedium();
            case "idle-large" -> runIdleLarge();
            default -> System.err.println("Unknown benchmark: " + benchmarkName);
        }
    }

    private void runLatencySmall() throws Exception {
        Path projectRoot = tempDir.resolve("latency_small");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.SMALL);

        FileWatcherBenchmark.BenchmarkResult legacyResult = benchmark.runLatencyBenchmark(projectRoot, 50, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult = benchmark.runLatencyBenchmark(projectRoot, 50, "native");

        writeResults(legacyResult, nativeResult);
    }

    private void runLatencyMedium() throws Exception {
        Path projectRoot = tempDir.resolve("latency_medium");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.MEDIUM);

        FileWatcherBenchmark.BenchmarkResult legacyResult = benchmark.runLatencyBenchmark(projectRoot, 30, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult = benchmark.runLatencyBenchmark(projectRoot, 30, "native");

        writeResults(legacyResult, nativeResult);
    }

    private void runLatencyLarge() throws Exception {
        Path projectRoot = tempDir.resolve("latency_large");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.LARGE);

        FileWatcherBenchmark.BenchmarkResult legacyResult = benchmark.runLatencyBenchmark(projectRoot, 20, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult = benchmark.runLatencyBenchmark(projectRoot, 20, "native");

        writeResults(legacyResult, nativeResult);
    }

    private void runBulkChangeSmall() throws Exception {
        Path projectRoot = tempDir.resolve("bulk_small");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.SMALL);

        FileWatcherBenchmark.BenchmarkResult legacyResult = benchmark.runBulkChangeBenchmark(projectRoot, 10, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult = benchmark.runBulkChangeBenchmark(projectRoot, 10, "native");

        writeResults(legacyResult, nativeResult);
    }

    private void runBulkChangeMedium() throws Exception {
        Path projectRoot = tempDir.resolve("bulk_medium");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.MEDIUM);

        FileWatcherBenchmark.BenchmarkResult legacyResult =
                benchmark.runBulkChangeBenchmark(projectRoot, 100, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult =
                benchmark.runBulkChangeBenchmark(projectRoot, 100, "native");

        writeResults(legacyResult, nativeResult);
    }

    private void runBulkChangeLarge() throws Exception {
        Path projectRoot = tempDir.resolve("bulk_large");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.SMALL);

        FileWatcherBenchmark.BenchmarkResult legacyResult =
                benchmark.runBulkChangeBenchmark(projectRoot, 500, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult =
                benchmark.runBulkChangeBenchmark(projectRoot, 500, "native");

        writeResults(legacyResult, nativeResult);
    }

    private void runIdleSmall() throws Exception {
        Path projectRoot = tempDir.resolve("idle_small");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.SMALL);

        FileWatcherBenchmark.BenchmarkResult legacyResult =
                benchmark.runIdleOverheadBenchmark(projectRoot, 10, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult =
                benchmark.runIdleOverheadBenchmark(projectRoot, 10, "native");

        writeResults(legacyResult, nativeResult);
    }

    private void runIdleMedium() throws Exception {
        Path projectRoot = tempDir.resolve("idle_medium");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.MEDIUM);

        FileWatcherBenchmark.BenchmarkResult legacyResult =
                benchmark.runIdleOverheadBenchmark(projectRoot, 10, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult =
                benchmark.runIdleOverheadBenchmark(projectRoot, 10, "native");

        writeResults(legacyResult, nativeResult);
    }

    private void runIdleLarge() throws Exception {
        Path projectRoot = tempDir.resolve("idle_large");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.LARGE);

        FileWatcherBenchmark.BenchmarkResult legacyResult =
                benchmark.runIdleOverheadBenchmark(projectRoot, 30, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult =
                benchmark.runIdleOverheadBenchmark(projectRoot, 30, "native");

        writeResults(legacyResult, nativeResult);
    }

    private void writeResults(
            FileWatcherBenchmark.BenchmarkResult legacyResult, FileWatcherBenchmark.BenchmarkResult nativeResult) {
        writeLine("");
        writeLine("LEGACY IMPLEMENTATION:");
        writeResult(legacyResult);

        writeLine("");
        writeLine("NATIVE IMPLEMENTATION:");
        writeResult(nativeResult);

        writeLine("");
        writeLine("COMPARISON:");
        writeComparison(legacyResult, nativeResult);
        writeLine("");

        // Also print to console
        legacyResult.printReport();
        nativeResult.printReport();
        printComparison(legacyResult, nativeResult);
    }

    private void writeResult(FileWatcherBenchmark.BenchmarkResult result) {
        writeLine(String.format("  Iterations: %d", result.iterations));
        writeLine(String.format("  P50 Latency: %.2f ms", result.getP50LatencyMs()));
        writeLine(String.format("  P95 Latency: %.2f ms", result.getP95LatencyMs()));
        writeLine(String.format("  P99 Latency: %.2f ms", result.getP99LatencyMs()));
        writeLine(String.format("  Avg Latency: %.2f ms", result.getAvgLatencyMs()));
        writeLine(String.format("  CPU Time: %.2f ms", result.getCpuTimeMs()));
        writeLine(String.format("  Wall Time: %.2f ms", result.getWallTimeMs()));
        writeLine(String.format("  Peak Memory: %.2f MB", result.getPeakMemoryMB()));
    }

    private void writeComparison(
            FileWatcherBenchmark.BenchmarkResult legacyResult, FileWatcherBenchmark.BenchmarkResult nativeResult) {
        double p50Improvement = calculateImprovement(legacyResult.getP50LatencyMs(), nativeResult.getP50LatencyMs());
        double p95Improvement = calculateImprovement(legacyResult.getP95LatencyMs(), nativeResult.getP95LatencyMs());
        double p99Improvement = calculateImprovement(legacyResult.getP99LatencyMs(), nativeResult.getP99LatencyMs());
        double cpuImprovement = calculateImprovement(legacyResult.getCpuTimeMs(), nativeResult.getCpuTimeMs());
        double memImprovement = calculateImprovement(legacyResult.getPeakMemoryMB(), nativeResult.getPeakMemoryMB());

        writeLine(String.format("  P50 Latency: %+.1f%% %s", p50Improvement, interpretImprovement(p50Improvement)));
        writeLine(String.format("  P95 Latency: %+.1f%% %s", p95Improvement, interpretImprovement(p95Improvement)));
        writeLine(String.format("  P99 Latency: %+.1f%% %s", p99Improvement, interpretImprovement(p99Improvement)));
        writeLine(String.format("  CPU Time: %+.1f%% %s", cpuImprovement, interpretImprovement(cpuImprovement)));
        writeLine(String.format("  Memory: %+.1f%% %s", memImprovement, interpretImprovement(memImprovement)));
    }

    private void printComparison(
            FileWatcherBenchmark.BenchmarkResult legacyResult, FileWatcherBenchmark.BenchmarkResult nativeResult) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("COMPARISON SUMMARY");
        System.out.println("=".repeat(80));

        double p50Improvement = calculateImprovement(legacyResult.getP50LatencyMs(), nativeResult.getP50LatencyMs());
        double p95Improvement = calculateImprovement(legacyResult.getP95LatencyMs(), nativeResult.getP95LatencyMs());
        double p99Improvement = calculateImprovement(legacyResult.getP99LatencyMs(), nativeResult.getP99LatencyMs());
        double cpuImprovement = calculateImprovement(legacyResult.getCpuTimeMs(), nativeResult.getCpuTimeMs());
        double memImprovement = calculateImprovement(legacyResult.getPeakMemoryMB(), nativeResult.getPeakMemoryMB());

        System.out.printf("  P50 Latency: %+.1f%% %s%n", p50Improvement, interpretImprovement(p50Improvement));
        System.out.printf("  P95 Latency: %+.1f%% %s%n", p95Improvement, interpretImprovement(p95Improvement));
        System.out.printf("  P99 Latency: %+.1f%% %s%n", p99Improvement, interpretImprovement(p99Improvement));
        System.out.printf("  CPU Time: %+.1f%% %s%n", cpuImprovement, interpretImprovement(cpuImprovement));
        System.out.printf("  Memory: %+.1f%% %s%n", memImprovement, interpretImprovement(memImprovement));
        System.out.println("=".repeat(80));
    }

    private double calculateImprovement(double legacy, double nativeVal) {
        if (legacy == 0) return 0;
        return ((legacy - nativeVal) / legacy) * 100;
    }

    private String interpretImprovement(double improvement) {
        if (improvement > 5) {
            return "(Native better)";
        } else if (improvement < -5) {
            return "(Legacy better)";
        } else {
            return "(Similar)";
        }
    }

    private void writeLine(String line) {
        writer.println(line);
        writer.flush();
    }

    private void cleanup() throws Exception {
        if (tempDir != null) {
            generator.deleteProject(tempDir);
        }
    }
}
