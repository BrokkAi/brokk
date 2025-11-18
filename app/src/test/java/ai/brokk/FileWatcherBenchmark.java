package ai.brokk;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Benchmark suite for comparing file watcher implementations.
 * Measures latency and system overhead (CPU, I/O) for ProjectWatchService.
 *
 * <p>Usage:
 * <pre>
 * FileWatcherBenchmark benchmark = new FileWatcherBenchmark();
 * BenchmarkResult result = benchmark.runLatencyBenchmark(testProjectPath, 100);
 * result.printReport();
 * </pre>
 */
public class FileWatcherBenchmark {

    private static final Logger logger = LogManager.getLogger(FileWatcherBenchmark.class);
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    static {
        // Enable CPU time measurement
        ManagementFactory.getThreadMXBean().setThreadCpuTimeEnabled(true);
    }

    /**
     * Results from a benchmark run.
     */
    public static class BenchmarkResult {
        public final String testName;
        public final int iterations;
        public final List<Long> latenciesNanos;
        public final long totalCpuTimeNanos;
        public final long totalWallTimeNanos;
        public final long peakMemoryBytes;
        public final Map<String, Object> metadata;

        public BenchmarkResult(
                String testName,
                int iterations,
                List<Long> latenciesNanos,
                long totalCpuTimeNanos,
                long totalWallTimeNanos,
                long peakMemoryBytes,
                Map<String, Object> metadata) {
            this.testName = testName;
            this.iterations = iterations;
            this.latenciesNanos = new ArrayList<>(latenciesNanos);
            this.totalCpuTimeNanos = totalCpuTimeNanos;
            this.totalWallTimeNanos = totalWallTimeNanos;
            this.peakMemoryBytes = peakMemoryBytes;
            this.metadata = new HashMap<>(metadata);
        }

        public double getP50LatencyMs() {
            return percentile(latenciesNanos, 0.50) / 1_000_000.0;
        }

        public double getP95LatencyMs() {
            return percentile(latenciesNanos, 0.95) / 1_000_000.0;
        }

        public double getP99LatencyMs() {
            return percentile(latenciesNanos, 0.99) / 1_000_000.0;
        }

        public double getAvgLatencyMs() {
            return latenciesNanos.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        }

        public double getCpuTimeMs() {
            return totalCpuTimeNanos / 1_000_000.0;
        }

        public double getWallTimeMs() {
            return totalWallTimeNanos / 1_000_000.0;
        }

        public double getPeakMemoryMB() {
            return peakMemoryBytes / (1024.0 * 1024.0);
        }

        private static double percentile(List<Long> sorted, double p) {
            if (sorted.isEmpty()) return 0;
            List<Long> copy = new ArrayList<>(sorted);
            Collections.sort(copy);
            int index = (int) Math.ceil(p * copy.size()) - 1;
            return copy.get(Math.max(0, index));
        }

        public void printReport() {
            System.out.println("=".repeat(80));
            System.out.println("Benchmark: " + testName);
            System.out.println("=".repeat(80));
            System.out.println("Iterations: " + iterations);
            System.out.println();
            System.out.println("Latency (ms):");
            System.out.printf("  P50: %.2f ms%n", getP50LatencyMs());
            System.out.printf("  P95: %.2f ms%n", getP95LatencyMs());
            System.out.printf("  P99: %.2f ms%n", getP99LatencyMs());
            System.out.printf("  Avg: %.2f ms%n", getAvgLatencyMs());
            System.out.println();
            System.out.println("System Resources:");
            System.out.printf("  CPU Time: %.2f ms%n", getCpuTimeMs());
            System.out.printf("  Wall Time: %.2f ms%n", getWallTimeMs());
            System.out.printf("  Peak Memory: %.2f MB%n", getPeakMemoryMB());
            System.out.println();
            if (!metadata.isEmpty()) {
                System.out.println("Metadata:");
                metadata.forEach((k, v) -> System.out.printf("  %s: %s%n", k, v));
            }
            System.out.println("=".repeat(80));
        }
    }

    /**
     * Metric collector for tracking resource usage during benchmark.
     */
    private static class MetricsCollector {
        private final List<Long> cpuTimeSamples = new ArrayList<>();
        private final List<Long> memorySamples = new ArrayList<>();
        private volatile boolean running = true;
        private Thread collectorThread;

        public void start() {
            collectorThread = new Thread(() -> {
                Runtime runtime = Runtime.getRuntime();
                ThreadMXBean bean = ManagementFactory.getThreadMXBean();

                while (running) {
                    long totalCpu = 0;
                    for (long threadId : bean.getAllThreadIds()) {
                        long cpu = bean.getThreadCpuTime(threadId);
                        if (cpu > 0) totalCpu += cpu;
                    }
                    cpuTimeSamples.add(totalCpu);

                    long usedMemory = runtime.totalMemory() - runtime.freeMemory();
                    memorySamples.add(usedMemory);

                    try {
                        Thread.sleep(50); // Sample every 50ms
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
            collectorThread.setDaemon(true);
            collectorThread.start();
        }

        public void stop() {
            running = false;
            if (collectorThread != null) {
                try {
                    collectorThread.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        public long getCpuTimeDelta() {
            if (cpuTimeSamples.size() < 2) return 0;
            return cpuTimeSamples.get(cpuTimeSamples.size() - 1) - cpuTimeSamples.get(0);
        }

        public long getPeakMemory() {
            return memorySamples.stream().mapToLong(Long::longValue).max().orElse(0);
        }
    }

    /**
     * Run latency benchmark measuring time from file modification to event detection.
     *
     * @param projectRoot Root of project to watch
     * @param iterations Number of file modifications to perform
     * @param implementation "native" or "legacy" to select implementation
     * @return Benchmark results
     */
    public BenchmarkResult runLatencyBenchmark(Path projectRoot, int iterations, String implementation)
            throws Exception {
        logger.info("Starting latency benchmark [{}]: {} iterations on {}", implementation, iterations, projectRoot);

        List<Long> latencies = new ArrayList<>();
        MetricsCollector metrics = new MetricsCollector();
        long startWallTime = System.nanoTime();

        // Create listener that measures latency
        // Use array to hold latch so we can reset it for each iteration
        final CountDownLatch[] currentLatch = new CountDownLatch[1];
        AtomicLong lastModificationTime = new AtomicLong();

        IWatchService.Listener listener = new IWatchService.Listener() {
            @Override
            public void onFilesChanged(IWatchService.EventBatch batch) {
                long detectionTime = System.nanoTime();
                long modTime = lastModificationTime.get();
                if (modTime > 0) {
                    long latency = detectionTime - modTime;
                    synchronized (latencies) {
                        latencies.add(latency);
                    }
                    if (currentLatch[0] != null) {
                        currentLatch[0].countDown();
                    }
                }
            }

            @Override
            public void onNoFilesChangedDuringPollInterval() {}
        };

        // Start watch service with selected implementation
        IWatchService watchService = createWatchService(projectRoot, implementation, List.of(listener));
        watchService.start(CompletableFuture.completedFuture(null));

        // Wait for watch service to initialize - longer for large projects
        long fileCount = countFiles(projectRoot);
        long initWaitMs = fileCount > 50000 ? 5000 : fileCount > 5000 ? 2000 : 500;
        logger.info("Waiting {}ms for watcher to initialize ({} files)", initWaitMs, fileCount);
        Thread.sleep(initWaitMs);

        metrics.start();

        // Create test file for modifications
        Path testFile = projectRoot.resolve("benchmark_test_file.txt");
        Files.writeString(testFile, "initial content");

        // Give watch service time to detect initial file - longer for large projects
        long settleMs = fileCount > 50000 ? 1000 : 500;
        Thread.sleep(settleMs);

        // Perform modifications and measure latency
        for (int i = 0; i < iterations; i++) {
            currentLatch[0] = new CountDownLatch(1);
            lastModificationTime.set(System.nanoTime());
            Files.writeString(testFile, "content " + i);

            // Wait for detection with timeout
            if (!currentLatch[0].await(10, TimeUnit.SECONDS)) {
                logger.warn("Timeout waiting for event {} of {}", i + 1, iterations);
                break;
            }

            // Small delay between iterations to avoid overwhelming
            Thread.sleep(100);
        }

        metrics.stop();
        long endWallTime = System.nanoTime();

        // Cleanup
        watchService.close();
        Files.deleteIfExists(testFile);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("implementation", implementation);
        metadata.put("projectPath", projectRoot.toString());
        metadata.put("fileCount", countFiles(projectRoot));
        metadata.put("eventsDetected", latencies.size());
        metadata.put("eventsExpected", iterations);

        if (latencies.size() < iterations) {
            logger.warn(
                    "Only detected {}/{} events for {} implementation", latencies.size(), iterations, implementation);
        }

        return new BenchmarkResult(
                "Latency Benchmark (" + implementation + ")",
                latencies.size(), // Use actual count of detected events
                latencies,
                metrics.getCpuTimeDelta(),
                endWallTime - startWallTime,
                metrics.getPeakMemory(),
                metadata);
    }

    private IWatchService createWatchService(
            Path projectRoot, String implementation, List<IWatchService.Listener> listeners) {
        if ("native".equalsIgnoreCase(implementation)) {
            return new NativeProjectWatchService(projectRoot, null, null, listeners);
        } else if ("legacy".equalsIgnoreCase(implementation)) {
            return new LegacyProjectWatchService(projectRoot, null, null, listeners);
        } else {
            throw new IllegalArgumentException("Unknown implementation: " + implementation);
        }
    }

    /**
     * Run bulk change benchmark measuring behavior with many simultaneous file changes.
     *
     * @param projectRoot Root of project to watch
     * @param fileCount Number of files to modify simultaneously
     * @param implementation "native" or "legacy" to select implementation
     * @return Benchmark results
     */
    public BenchmarkResult runBulkChangeBenchmark(Path projectRoot, int fileCount, String implementation)
            throws Exception {
        logger.info("Starting bulk change benchmark [{}]: {} files on {}", implementation, fileCount, projectRoot);

        List<Long> latencies = new ArrayList<>();
        MetricsCollector metrics = new MetricsCollector();
        long startWallTime = System.nanoTime();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong bulkModificationTime = new AtomicLong();

        IWatchService.Listener listener = new IWatchService.Listener() {
            @Override
            public void onFilesChanged(IWatchService.EventBatch batch) {
                long detectionTime = System.nanoTime();
                long modTime = bulkModificationTime.get();
                if (modTime > 0) {
                    long latency = detectionTime - modTime;
                    latencies.add(latency);
                    logger.info(
                            "Detected bulk change: {} files, overflow={}, latency={}ms",
                            batch.files.size(),
                            batch.isOverflowed,
                            latency / 1_000_000.0);
                    latch.countDown();
                }
            }

            @Override
            public void onNoFilesChangedDuringPollInterval() {}
        };

        IWatchService watchService = createWatchService(projectRoot, implementation, List.of(listener));
        watchService.start(CompletableFuture.completedFuture(null));
        Thread.sleep(500);

        metrics.start();

        // Create test files
        Path testDir = projectRoot.resolve("benchmark_bulk_test");
        Files.createDirectories(testDir);
        List<Path> testFiles = new ArrayList<>();

        for (int i = 0; i < fileCount; i++) {
            Path file = testDir.resolve("file_" + i + ".txt");
            Files.writeString(file, "initial");
            testFiles.add(file);
        }

        Thread.sleep(500); // Let initial creation events settle

        // Modify all files simultaneously
        bulkModificationTime.set(System.nanoTime());
        for (int i = 0; i < fileCount; i++) {
            Files.writeString(testFiles.get(i), "modified " + i);
        }

        // Wait for detection
        boolean detected = latch.await(10, TimeUnit.SECONDS);
        if (!detected) {
            logger.warn("Timeout waiting for bulk change detection");
        }

        metrics.stop();
        long endWallTime = System.nanoTime();

        // Cleanup
        watchService.close();
        for (Path file : testFiles) {
            Files.deleteIfExists(file);
        }
        Files.deleteIfExists(testDir);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("implementation", implementation);
        metadata.put("projectPath", projectRoot.toString());
        metadata.put("bulkFileCount", fileCount);
        metadata.put("detected", detected);

        return new BenchmarkResult(
                "Bulk Change Benchmark (" + implementation + ")",
                1,
                latencies,
                metrics.getCpuTimeDelta(),
                endWallTime - startWallTime,
                metrics.getPeakMemory(),
                metadata);
    }

    /**
     * Run idle overhead benchmark measuring resource usage when no changes occur.
     *
     * @param projectRoot Root of project to watch
     * @param durationSeconds How long to monitor (seconds)
     * @param implementation "native" or "legacy" to select implementation
     * @return Benchmark results
     */
    public BenchmarkResult runIdleOverheadBenchmark(Path projectRoot, int durationSeconds, String implementation)
            throws Exception {
        logger.info("Starting idle overhead benchmark [{}]: {}s on {}", implementation, durationSeconds, projectRoot);

        MetricsCollector metrics = new MetricsCollector();
        long startWallTime = System.nanoTime();

        IWatchService.Listener listener = new IWatchService.Listener() {
            @Override
            public void onFilesChanged(IWatchService.EventBatch batch) {
                logger.warn("Unexpected file change during idle benchmark: {}", batch);
            }

            @Override
            public void onNoFilesChangedDuringPollInterval() {}
        };

        IWatchService watchService = createWatchService(projectRoot, implementation, List.of(listener));
        watchService.start(CompletableFuture.completedFuture(null));

        Thread.sleep(500); // Startup time

        metrics.start();

        // Just wait and measure overhead
        Thread.sleep(durationSeconds * 1000L);

        metrics.stop();
        long endWallTime = System.nanoTime();

        watchService.close();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("implementation", implementation);
        metadata.put("projectPath", projectRoot.toString());
        metadata.put("fileCount", countFiles(projectRoot));
        metadata.put("durationSeconds", durationSeconds);

        return new BenchmarkResult(
                "Idle Overhead Benchmark (" + implementation + ")",
                0,
                Collections.emptyList(),
                metrics.getCpuTimeDelta(),
                endWallTime - startWallTime,
                metrics.getPeakMemory(),
                metadata);
    }

    private long countFiles(Path root) {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return -1;
        }
    }
}
