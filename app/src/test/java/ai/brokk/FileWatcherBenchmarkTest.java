package ai.brokk;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.*;

/**
 * JUnit tests that run file watcher benchmarks.
 * These tests measure latency and overhead metrics.
 *
 * <p>Note: These are not typical unit tests - they're performance benchmarks.
 * Run them manually or with a performance testing profile.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// @Disabled("Benchmark tests - enable individually for performance measurements")
public class FileWatcherBenchmarkTest {

    private static Path tempDir;
    private static TestProjectGenerator generator;
    private static FileWatcherBenchmark benchmark;

    @BeforeAll
    static void setUp() throws Exception {
        tempDir = Files.createTempDirectory("filewatcher_benchmark_");
        generator = new TestProjectGenerator();
        benchmark = new FileWatcherBenchmark();
        System.out.println("Benchmark temporary directory: " + tempDir);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (tempDir != null) {
            generator.deleteProject(tempDir);
        }
    }

    @Test
    @Order(1)
    @Tag("benchmark")
    void testLatencySmallProject() throws Exception {
        Path projectRoot = tempDir.resolve("small_project");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.SMALL);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("COMPARISON: Latency on Small Project");
        System.out.println("=".repeat(80));

        FileWatcherBenchmark.BenchmarkResult legacyResult = benchmark.runLatencyBenchmark(projectRoot, 50, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult = benchmark.runLatencyBenchmark(projectRoot, 50, "native");

        legacyResult.printReport();
        nativeResult.printReport();

        printComparison(legacyResult, nativeResult);

        // Basic sanity checks
        Assertions.assertTrue(legacyResult.getP50LatencyMs() < 1000, "Legacy P50 latency should be < 1s");
        Assertions.assertTrue(nativeResult.getP50LatencyMs() < 1000, "Native P50 latency should be < 1s");
    }

    @Test
    @Order(2)
    @Tag("benchmark")
    void testLatencyMediumProject() throws Exception {
        Path projectRoot = tempDir.resolve("medium_project");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.MEDIUM);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("COMPARISON: Latency on Medium Project");
        System.out.println("=".repeat(80));

        FileWatcherBenchmark.BenchmarkResult legacyResult = benchmark.runLatencyBenchmark(projectRoot, 30, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult = benchmark.runLatencyBenchmark(projectRoot, 30, "native");

        legacyResult.printReport();
        nativeResult.printReport();

        printComparison(legacyResult, nativeResult);

        Assertions.assertTrue(legacyResult.getP50LatencyMs() < 1000, "Legacy P50 latency should be < 1s");
        Assertions.assertTrue(nativeResult.getP50LatencyMs() < 1000, "Native P50 latency should be < 1s");
    }

    @Test
    @Order(3)
    @Tag("benchmark")
    void testLatencyLargeProject() throws Exception {
        Path projectRoot = tempDir.resolve("large_project");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.LARGE);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("COMPARISON: Latency on Large Project");
        System.out.println("=".repeat(80));

        FileWatcherBenchmark.BenchmarkResult legacyResult = benchmark.runLatencyBenchmark(projectRoot, 20, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult = benchmark.runLatencyBenchmark(projectRoot, 20, "native");

        legacyResult.printReport();
        nativeResult.printReport();

        printComparison(legacyResult, nativeResult);

        Assertions.assertTrue(legacyResult.getP50LatencyMs() < 2000, "Legacy P50 latency should be < 2s");
        Assertions.assertTrue(nativeResult.getP50LatencyMs() < 2000, "Native P50 latency should be < 2s");
    }

    @Test
    @Order(4)
    @Tag("benchmark")
    void testBulkChangeSmall() throws Exception {
        Path projectRoot = tempDir.resolve("bulk_small");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.SMALL);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("COMPARISON: Bulk Change Small");
        System.out.println("=".repeat(80));

        FileWatcherBenchmark.BenchmarkResult legacyResult = benchmark.runBulkChangeBenchmark(projectRoot, 10, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult = benchmark.runBulkChangeBenchmark(projectRoot, 10, "native");

        legacyResult.printReport();
        nativeResult.printReport();

        printComparison(legacyResult, nativeResult);
    }

    @Test
    @Order(5)
    @Tag("benchmark")
    void testBulkChangeMedium() throws Exception {
        Path projectRoot = tempDir.resolve("bulk_medium");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.MEDIUM);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("COMPARISON: Bulk Change Medium");
        System.out.println("=".repeat(80));

        FileWatcherBenchmark.BenchmarkResult legacyResult =
                benchmark.runBulkChangeBenchmark(projectRoot, 100, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult =
                benchmark.runBulkChangeBenchmark(projectRoot, 100, "native");

        legacyResult.printReport();
        nativeResult.printReport();

        printComparison(legacyResult, nativeResult);
    }

    @Test
    @Order(6)
    @Tag("benchmark")
    void testBulkChangeLarge() throws Exception {
        Path projectRoot = tempDir.resolve("bulk_large");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.SMALL);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("COMPARISON: Bulk Change Large");
        System.out.println("=".repeat(80));

        FileWatcherBenchmark.BenchmarkResult legacyResult =
                benchmark.runBulkChangeBenchmark(projectRoot, 500, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult =
                benchmark.runBulkChangeBenchmark(projectRoot, 500, "native");

        legacyResult.printReport();
        nativeResult.printReport();

        printComparison(legacyResult, nativeResult);
    }

    @Test
    @Order(7)
    @Tag("benchmark")
    void testIdleOverheadSmallProject() throws Exception {
        Path projectRoot = tempDir.resolve("idle_small");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.SMALL);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("COMPARISON: Idle Overhead Small");
        System.out.println("=".repeat(80));

        FileWatcherBenchmark.BenchmarkResult legacyResult =
                benchmark.runIdleOverheadBenchmark(projectRoot, 10, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult =
                benchmark.runIdleOverheadBenchmark(projectRoot, 10, "native");

        legacyResult.printReport();
        nativeResult.printReport();

        printComparison(legacyResult, nativeResult);
    }

    @Test
    @Order(8)
    @Tag("benchmark")
    void testIdleOverheadMediumProject() throws Exception {
        Path projectRoot = tempDir.resolve("idle_medium");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.MEDIUM);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("COMPARISON: Idle Overhead Medium");
        System.out.println("=".repeat(80));

        FileWatcherBenchmark.BenchmarkResult legacyResult =
                benchmark.runIdleOverheadBenchmark(projectRoot, 10, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult =
                benchmark.runIdleOverheadBenchmark(projectRoot, 10, "native");

        legacyResult.printReport();
        nativeResult.printReport();

        printComparison(legacyResult, nativeResult);
    }

    @Test
    @Order(9)
    @Tag("benchmark")
    void testIdleOverheadLargeProject() throws Exception {
        Path projectRoot = tempDir.resolve("idle_large");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.LARGE);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("COMPARISON: Idle Overhead Large");
        System.out.println("=".repeat(80));

        FileWatcherBenchmark.BenchmarkResult legacyResult =
                benchmark.runIdleOverheadBenchmark(projectRoot, 30, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult =
                benchmark.runIdleOverheadBenchmark(projectRoot, 30, "native");

        legacyResult.printReport();
        nativeResult.printReport();

        printComparison(legacyResult, nativeResult);
    }

    @Test
    @Order(10)
    @Tag("benchmark")
    void testGitMetadataChanges() throws Exception {
        Path projectRoot = tempDir.resolve("git_project");
        generator.generateProject(projectRoot, TestProjectGenerator.ProjectSize.SMALL);
        generator.createGitStructure(projectRoot);

        System.out.println("\n" + "=".repeat(80));
        System.out.println("COMPARISON: Git Project Latency");
        System.out.println("=".repeat(80));

        FileWatcherBenchmark.BenchmarkResult legacyResult = benchmark.runLatencyBenchmark(projectRoot, 20, "legacy");
        FileWatcherBenchmark.BenchmarkResult nativeResult = benchmark.runLatencyBenchmark(projectRoot, 20, "native");

        legacyResult.printReport();
        nativeResult.printReport();

        printComparison(legacyResult, nativeResult);

        Assertions.assertTrue(legacyResult.getP50LatencyMs() < 1000, "Legacy git project latency should be < 1s");
        Assertions.assertTrue(nativeResult.getP50LatencyMs() < 1000, "Native git project latency should be < 1s");
    }

    /**
     * Print a comparison summary of two benchmark results.
     */
    private void printComparison(
            FileWatcherBenchmark.BenchmarkResult legacyResult, FileWatcherBenchmark.BenchmarkResult nativeResult) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("COMPARISON SUMMARY");
        System.out.println("=".repeat(80));

        double p50Improvement =
                ((legacyResult.getP50LatencyMs() - nativeResult.getP50LatencyMs()) / legacyResult.getP50LatencyMs())
                        * 100;
        double p95Improvement =
                ((legacyResult.getP95LatencyMs() - nativeResult.getP95LatencyMs()) / legacyResult.getP95LatencyMs())
                        * 100;
        double p99Improvement =
                ((legacyResult.getP99LatencyMs() - nativeResult.getP99LatencyMs()) / legacyResult.getP99LatencyMs())
                        * 100;
        double cpuReduction =
                ((legacyResult.getCpuTimeMs() - nativeResult.getCpuTimeMs()) / legacyResult.getCpuTimeMs()) * 100;
        double memoryReduction =
                ((legacyResult.getPeakMemoryMB() - nativeResult.getPeakMemoryMB()) / legacyResult.getPeakMemoryMB())
                        * 100;

        System.out.println("Latency Improvements (Native vs Legacy):");
        System.out.printf("  P50: %+.1f%% (%s)%n", p50Improvement, formatImprovement(p50Improvement));
        System.out.printf("  P95: %+.1f%% (%s)%n", p95Improvement, formatImprovement(p95Improvement));
        System.out.printf("  P99: %+.1f%% (%s)%n", p99Improvement, formatImprovement(p99Improvement));
        System.out.println();
        System.out.println("Resource Usage (Native vs Legacy):");
        System.out.printf("  CPU Time: %+.1f%% (%s)%n", cpuReduction, formatImprovement(cpuReduction));
        System.out.printf("  Peak Memory: %+.1f%% (%s)%n", memoryReduction, formatImprovement(memoryReduction));
        System.out.println("=".repeat(80));
    }

    private String formatImprovement(double percentage) {
        if (percentage > 0) {
            return "Native faster/less";
        } else if (percentage < 0) {
            return "Legacy faster/less";
        } else {
            return "Same";
        }
    }
}
