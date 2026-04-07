package ai.brokk.tools;

import ai.brokk.ContextManager;
import ai.brokk.project.MainProject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.jspecify.annotations.NullMarked;
import picocli.CommandLine;

@NullMarked
@CommandLine.Command(
        name = "SearchFileContentsBenchmark",
        mixinStandardHelpOptions = true,
        description = "Benchmarks SearchTools.searchFileContents against rg on a real repo")
public class SearchFileContentsBenchmark implements Callable<Integer> {

    @CommandLine.Option(
            names = {"--project"},
            required = true,
            description = "Absolute or relative path to the project root to benchmark")
    private Path project = Path.of(".");

    @CommandLine.Option(
            names = {"--glob"},
            defaultValue = "**/*.java",
            description = "Glob used by both internal search and rg (default: ${DEFAULT-VALUE})")
    private String glob = "**/*.java";

    @CommandLine.Option(
            names = {"--warm-up-iterations"},
            defaultValue = "1",
            description = "Number of warmup iterations per scenario (default: ${DEFAULT-VALUE})")
    private int warmUpIterations = 1;

    @CommandLine.Option(
            names = {"--iterations"},
            defaultValue = "5",
            description = "Number of measured iterations per scenario (default: ${DEFAULT-VALUE})")
    private int iterations = 5;

    @CommandLine.Option(
            names = {"--max-files"},
            defaultValue = "50",
            description = "maxFiles passed to searchFileContents (default: ${DEFAULT-VALUE})")
    private int maxFiles = 50;

    @CommandLine.Option(
            names = {"--matches-per-file"},
            defaultValue = "10",
            description = "max matches passed to rg -m (default: ${DEFAULT-VALUE})")
    private int matchesPerFile = 10;

    @CommandLine.Option(
            names = {"--context-lines"},
            defaultValue = "0",
            description = "contextLines passed to searchFileContents (default: ${DEFAULT-VALUE})")
    private int contextLines = 0;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SearchFileContentsBenchmark()).execute(args);
        System.exit(exitCode);
    }

    private record Scenario(String name, String pattern) {}

    private record Metrics(long minNanos, long medianNanos, long avgNanos, long maxNanos) {}

    @Override
    public Integer call() throws Exception {
        Path root = project.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("Project path is not a directory: " + root);
            return 1;
        }

        if (!isRgAvailable(root)) {
            System.err.println("rg is not available on PATH. Install ripgrep to run this benchmark.");
            return 1;
        }

        if (warmUpIterations < 0 || iterations <= 0) {
            System.err.println("--warm-up-iterations must be >= 0 and --iterations must be > 0");
            return 1;
        }

        var scenarios = List.of(
                new Scenario("literal-miss", "BROKK_SEARCH_BENCHMARK_NO_MATCH_9f8d7c"),
                new Scenario("literal-sparse-hit", "searchFileContents"),
                new Scenario("literal-dense-hit", "import"));

        System.out.println("SearchFileContents benchmark");
        System.out.printf(Locale.ROOT, "Project: %s%n", root);
        System.out.printf(
                Locale.ROOT,
                "Glob: %s | warmups=%d | iterations=%d | maxFiles=%d | matchesPerFile=%d | contextLines=%d%n%n",
                glob,
                warmUpIterations,
                iterations,
                maxFiles,
                matchesPerFile,
                contextLines);

        try (var mainProject = MainProject.forTests(root);
                var cm = new ContextManager(mainProject)) {
            var searchTools = new SearchTools(cm);
            for (Scenario scenario : scenarios) {
                runScenario(root, searchTools, scenario);
            }
        }

        return 0;
    }

    private void runScenario(Path root, SearchTools searchTools, Scenario scenario) throws Exception {
        for (int i = 0; i < warmUpIterations; i++) {
            runInternal(searchTools, scenario.pattern());
            runRg(root, scenario.pattern());
        }

        List<Long> internalNanos = new ArrayList<>();
        List<Long> rgNanos = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            boolean internalFirst = (i % 2) == 0;
            if (internalFirst) {
                internalNanos.add(timeNanos(() -> runInternal(searchTools, scenario.pattern())));
                rgNanos.add(timeNanos(() -> runRg(root, scenario.pattern())));
            } else {
                rgNanos.add(timeNanos(() -> runRg(root, scenario.pattern())));
                internalNanos.add(timeNanos(() -> runInternal(searchTools, scenario.pattern())));
            }
        }

        Metrics internalMetrics = metrics(internalNanos);
        Metrics rgMetrics = metrics(rgNanos);
        double slowdown = rgMetrics.avgNanos() == 0
                ? Double.POSITIVE_INFINITY
                : (double) internalMetrics.avgNanos() / (double) rgMetrics.avgNanos();

        System.out.println("Scenario: " + scenario.name() + " (pattern: " + scenario.pattern() + ")");
        System.out.println("  internal: " + formatMetrics(internalMetrics));
        System.out.println("  rg      : " + formatMetrics(rgMetrics));
        System.out.printf(Locale.ROOT, "  slowdown internal/rg: %.2fx%n%n", slowdown);
    }

    private String runInternal(SearchTools searchTools, String pattern) throws InterruptedException {
        return searchTools.searchFileContents(List.of(pattern), glob, false, false, contextLines, maxFiles);
    }

    private String runRg(Path root, String pattern) throws IOException, InterruptedException {
        var command = List.of(
                "rg",
                "--hidden",
                "--no-ignore",
                "--line-number",
                "--glob",
                glob,
                "-m",
                Integer.toString(matchesPerFile),
                pattern,
                ".");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(root.toFile());

        Process process = pb.start();
        byte[] outputBytes = readAll(process);
        int exit = process.waitFor();
        if (exit != 0 && exit != 1) {
            String output = new String(outputBytes);
            throw new IOException("rg failed with exit " + exit + ": " + output);
        }
        return new String(outputBytes);
    }

    private static byte[] readAll(Process process) {
        try (var stdout = process.getInputStream();
                var stderr = process.getErrorStream();
                var baos = new ByteArrayOutputStream()) {
            stdout.transferTo(baos);
            stderr.transferTo(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long timeNanos(ThrowingRunnable runnable) throws Exception {
        long start = System.nanoTime();
        runnable.run();
        return System.nanoTime() - start;
    }

    private static Metrics metrics(List<Long> samples) {
        List<Long> sorted = samples.stream().sorted(Comparator.naturalOrder()).toList();
        long min = sorted.getFirst();
        long max = sorted.getLast();
        long median = sorted.get(sorted.size() / 2);
        long avg = (long) sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        return new Metrics(min, median, avg, max);
    }

    private static String formatMetrics(Metrics metrics) {
        return "avg=%s median=%s min=%s max=%s"
                .formatted(
                        nanosToMs(metrics.avgNanos()),
                        nanosToMs(metrics.medianNanos()),
                        nanosToMs(metrics.minNanos()),
                        nanosToMs(metrics.maxNanos()));
    }

    private static String nanosToMs(long nanos) {
        return "%.2fms".formatted(nanos / 1_000_000.0);
    }

    private static boolean isRgAvailable(Path root) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("rg", "--version");
        pb.directory(root.toFile());
        Process process = pb.start();
        readAll(process);
        return process.waitFor() == 0;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
