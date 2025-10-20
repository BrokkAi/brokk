package io.github.jbellis.brokk.tools;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.*;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Simplified baseline measurement utility for TreeSitter performance analysis. Compatible with master branch - uses
 * basic caching and simplified metrics.
 *
 * <p>Usage: java TreeSitterRepoRunner [options] <command>
 *
 * <p>Commands: setup-projects Download/clone all test projects run-baselines Execute full baseline suite test-project
 * Test specific project: --project <name> --language <lang> memory-stress Memory stress test with increasing file
 * counts multi-language Multi-language analysis on same project
 *
 * <p>Options: --project <name> Specific project (chromium, llvm, vscode, etc.) --language <lang> Language to analyze
 * (cpp, java, typescript, etc.) --directory <path> Custom directory to analyze (use absolute paths) --max-files <count>
 * Maximum files to process (default: 1000) --output <path> Output directory for results (default:
 * build/reports/treesitter-baseline) --memory-profile Enable detailed memory profiling --stress-test Run until
 * OutOfMemoryError to find limits --json Output results in JSON format --verbose Enable verbose logging --show-details
 * Show symbols found in each file
 */
public class TreeSitterRepoRunner {

    private static final String PROJECTS_DIR = "../test-projects";
    private static final String DEFAULT_OUTPUT_DIR = "build/reports/treesitter-baseline";

    /**
     * Base directory where test projects are stored. Defaults to {@link #PROJECTS_DIR} but may be overridden with the
     * --projects-dir CLI option.
     */
    private Path projectsBaseDir = Paths.get(PROJECTS_DIR).toAbsolutePath().normalize();

    // Project configurations for real-world testing
    private static final Map<String, ProjectConfig> PROJECTS;

    static {
        var projects = new HashMap<String, ProjectConfig>();
        projects.put(
                "chromium",
                new ProjectConfig(
                        "https://chromium.googlesource.com/chromium/src.git",
                        "main",
                        Map.of(
                                "cpp", List.of("**/*.cc", "**/*.cpp", "**/*.h", "**/*.hpp"),
                                "javascript", List.of("**/*.js"),
                                "python", List.of("**/*.py")),
                        List.of("third_party/**", "out/**", "build/**", "node_modules/**")));
        projects.put(
                "llvm",
                new ProjectConfig(
                        "https://github.com/llvm/llvm-project.git",
                        "main",
                        Map.of("cpp", List.of("**/*.cpp", "**/*.h", "**/*.c")),
                        List.of("**/test/**", "**/examples/**")));
        projects.put(
                "vscode",
                new ProjectConfig(
                        "https://github.com/microsoft/vscode.git",
                        "main",
                        Map.of(
                                "typescript", List.of("**/*.ts"),
                                "javascript", List.of("**/*.js")),
                        List.of("node_modules/**", "out/**", "extensions/**/node_modules/**")));
        projects.put(
                "openjdk",
                new ProjectConfig(
                        "https://github.com/openjdk/jdk.git",
                        "master",
                        Map.of("java", List.of("**/*.java")),
                        List.of("**/test/**", "build/**", "**/gensrc/**")));
        projects.put(
                "spring-framework",
                new ProjectConfig(
                        "https://github.com/spring-projects/spring-framework.git",
                        "main",
                        Map.of("java", List.of("**/*.java")),
                        List.of("**/test/**", "build/**", "**/testData/**")));
        projects.put(
                "kafka",
                new ProjectConfig(
                        "https://github.com/apache/kafka.git",
                        "trunk",
                        Map.of("java", List.of("**/*.java")),
                        List.of("**/test/**", "build/**", "**/target/**")));
        projects.put(
                "elasticsearch",
                new ProjectConfig(
                        "https://github.com/elastic/elasticsearch.git",
                        "main",
                        Map.of("java", List.of("**/*.java")),
                        List.of("**/test/**", "build/**", "**/target/**")));
        projects.put(
                "intellij-community",
                new ProjectConfig(
                        "https://github.com/JetBrains/intellij-community.git",
                        "master",
                        Map.of("java", List.of("**/*.java")),
                        List.of("**/test/**", "build/**", "**/testData/**")));
        projects.put(
                "hibernate-orm",
                new ProjectConfig(
                        "https://github.com/hibernate/hibernate-orm.git",
                        "main",
                        Map.of("java", List.of("**/*.java")),
                        List.of("**/test/**", "build/**", "**/target/**")));
        PROJECTS = Map.copyOf(projects);
    }

    // Default glob patterns per language, used when stressing an arbitrary directory
    private static final Map<String, List<String>> DEFAULT_LANGUAGE_PATTERNS = Map.of(
            "java", List.of("**/*.java"),
            "cpp", List.of("**/*.c", "**/*.cc", "**/*.cpp", "**/*.h", "**/*.hpp"),
            "typescript", List.of("**/*.ts"),
            "javascript", List.of("**/*.js"),
            "python", List.of("**/*.py"));

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

    private boolean memoryProfiling = false;
    private boolean stressTest = false;
    private boolean jsonOutput = false;
    private boolean verbose = false;
    private boolean showDetails = false;
    private boolean cleanupReports = false;
    private int maxFiles = 1000;
    private Path outputDir = Paths.get(DEFAULT_OUTPUT_DIR);
    private String testProject = null;
    private String testLanguage = null;
    private Path testDirectory = null;

    public static void main(String[] args) {
        new TreeSitterRepoRunner().run(args);
    }

    private void run(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        try {
            String command = parseArgumentsAndGetCommand(args);

            if (cleanupReports) {
                cleanupReportsDirectory();
                if (!"cleanup".equals(command)) {
                    System.out.println("Reports cleaned. Continuing with command: " + command);
                }
            }

            ensureOutputDirectory();
            printStartupBanner(command);

            switch (command) {
                case "setup-projects" -> setupProjects();
                case "run-baselines" -> runFullBaselines();
                case "test-project" -> testSpecificProject();
                case "memory-stress" -> memoryStressTest();
                case "multi-language" -> multiLanguageAnalysis();
                case "cleanup" -> {
                    System.out.println("✓ Reports directory cleaned");
                    return;
                }
                case "help" -> {
                    printUsage();
                    return;
                }
                default -> {
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
                }
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private void setupProjects() throws Exception {
        System.out.println("Setting up test projects...");

        // Ensure the base directory exists
        Files.createDirectories(projectsBaseDir);

        for (var entry : PROJECTS.entrySet()) {
            var projectName = entry.getKey();
            var config = entry.getValue();

            var projectPath = projectsBaseDir.resolve(projectName);

            if (!Files.exists(projectPath)) {
                System.out.println("Cloning " + projectName + "...");
                cloneProject(config, projectPath);
                System.out.println("✓ " + projectName + " cloned successfully");
            } else {
                System.out.println("✓ " + projectName + " already exists");
            }
        }

        System.out.println("All projects ready for baseline testing");
    }

    private void runFullBaselines() throws Exception {
        System.out.println("Running comprehensive baselines...");

        var timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        var resultFile = outputDir.resolve("baseline-" + timestamp + ".json");
        var results = new BaselineResults();

        // Test each project with primary language (ordered by complexity)
        var projectTests = new LinkedHashMap<String, String>();
        projectTests.put("kafka", "java"); // Start with medium Java project
        projectTests.put("hibernate-orm", "java"); // ORM framework complexity
        projectTests.put("vscode", "typescript"); // TypeScript complexity
        projectTests.put("spring-framework", "java"); // Enterprise framework patterns
        projectTests.put("elasticsearch", "java"); // Large search engine
        projectTests.put("intellij-community", "java"); // IDE complexity
        projectTests.put("openjdk", "java"); // Massive Java runtime
        projectTests.put("llvm", "cpp"); // Large C++ complexity
        projectTests.put("chromium", "cpp"); // Largest - expect failure/OOM

        for (var entry : projectTests.entrySet()) {
            String project = entry.getKey();
            String language = entry.getValue();

            System.out.println("\n=== BASELINE: " + project + " (" + language + ") ===");

            try {
                System.out.println("Project path: " + getProjectPath(project));
                var result = runProjectBaseline(project, language);
                results.addResult(project, language, result);

                // Write incremental reports immediately
                try {
                    results.saveIncrementalResult(project, language, result, outputDir, timestamp);
                    System.out.println("📊 Incremental results saved");
                } catch (Exception e) {
                    System.err.println("⚠ Failed to save incremental results: " + e.getMessage());
                }

                // Print immediate results
                System.out.printf("Files processed: %d%n", result.filesProcessed);
                System.out.printf("Analysis time: %.2f seconds%n", result.duration.toMillis() / 1000.0);
                System.out.printf("Peak memory: %.1f MB%n", result.peakMemoryMB);
                System.out.printf("Memory per file: %.1f KB%n", result.peakMemoryMB * 1024 / result.filesProcessed);

                if (result.failed) {
                    System.out.println("❌ Analysis failed: " + result.failureReason);
                    results.recordFailure(project, language, result.failureReason);
                } else {
                    System.out.println("✓ Analysis completed successfully");
                }

            } catch (OutOfMemoryError e) {
                System.out.println("❌ OutOfMemoryError - scalability limit reached");
                results.recordOOM(project, language, maxFiles);
                // Write incremental results for OOM failure
                try {
                    var failedResult =
                            new BaselineResult(maxFiles, 0, Duration.ZERO, 0, 0, true, "OutOfMemoryError", null, 0, 0);
                    results.saveIncrementalResult(project, language, failedResult, outputDir, timestamp);
                    System.out.println("📊 Incremental failure result saved");
                } catch (Exception ex) {
                    System.err.println("⚠ Failed to save incremental failure result: " + ex.getMessage());
                }
            } catch (Exception e) {
                System.out.println("❌ Failed: " + e.getMessage());
                results.recordError(project, language, e.getMessage());
                // Write incremental results for general failure
                try {
                    var failedResult = new BaselineResult(0, 0, Duration.ZERO, 0, 0, true, e.getMessage(), null, 0, 0);
                    results.saveIncrementalResult(project, language, failedResult, outputDir, timestamp);
                    System.out.println("📊 Incremental failure result saved");
                } catch (Exception ex) {
                    System.err.println("⚠ Failed to save incremental failure result: " + ex.getMessage());
                }
            }
        }

        // Save comprehensive results
        results.saveToFile(resultFile);

        // Save additional format files
        var csvFile = outputDir.resolve("baseline-" + timestamp + ".csv");
        results.saveToCsv(csvFile);

        var summaryFile = outputDir.resolve("baseline-" + timestamp + "-summary.txt");
        results.saveTextSummary(summaryFile);

        System.out.println("\nBaseline results saved:");
        System.out.println("  JSON: " + resultFile);
        System.out.println("  CSV:  " + csvFile);
        System.out.println("  TXT:  " + summaryFile);

        // Print summary
        printBaselineSummary(results);
    }

    private BaselineResult runProjectBaseline(String projectName, String language) throws Exception {
        var projectPath = getProjectPath(projectName);
        var files = getProjectFiles(projectName, language, maxFiles);

        if (files.isEmpty()) {
            throw new RuntimeException("No " + language + " files found in " + projectName);
        }

        var analyzer = createAnalyzer(projectPath, language, files);
        if (analyzer == null) {
            throw new RuntimeException("Could not create analyzer for " + language);
        }

        // Start profiling - force GC to establish clean baseline
        System.gc();
        try {
            Thread.sleep(100); // Allow GC to complete
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        var memoryMonitor = memoryProfiling ? startMemoryMonitoring() : null;
        var startTime = System.nanoTime();
        var startMemory = memoryBean.getHeapMemoryUsage().getUsed();
        var gcStartCollections = gcBeans.stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionCount()))
                .sum();
        var gcStartTime = gcBeans.stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionTime()))
                .sum();

        try {
            // Execute analysis - force parsing of all files by processing each one
            var declarations = files.stream()
                    .map(file -> {
                        // This triggers actual TreeSitter parsing for each file
                        var fileDeclarations = analyzer.getDeclarationsInFile(file);

                        // Show detailed symbol information if requested
                        if (showDetails) {
                            System.out.printf("📄 %s (%d symbols):%n", file.getRelPath(), fileDeclarations.size());
                            fileDeclarations.forEach(declaration ->
                                    System.out.printf("  - %s: %s%n", declaration.kind(), declaration.shortName()));
                            System.out.println();
                        }

                        return fileDeclarations;
                    })
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());

            var endTime = System.nanoTime();
            var gcEndCollections = gcBeans.stream()
                    .mapToLong(bean -> Math.max(0, bean.getCollectionCount()))
                    .sum();
            var gcEndTime = gcBeans.stream()
                    .mapToLong(bean -> Math.max(0, bean.getCollectionTime()))
                    .sum();
            var gcCollectionsDelta = gcEndCollections - gcStartCollections;
            var gcTimeDelta = gcEndTime - gcStartTime;

            if (memoryMonitor != null) {
                memoryMonitor.stop();
            }

            var duration = Duration.ofNanos(endTime - startTime);
            // Use allocation-based measurement: peak - start (always positive)
            var peakMemory = memoryProfiling
                    ? memoryMonitor.getPeak()
                    : memoryBean.getHeapMemoryUsage().getUsed();
            var memoryDelta = peakMemory - startMemory;

            return new BaselineResult(
                    files.size(),
                    declarations.size(),
                    duration,
                    memoryDelta / (1024.0 * 1024.0), // Convert to MB
                    peakMemory / (1024.0 * 1024.0), // Convert to MB
                    false,
                    null,
                    getBasicStats(analyzer),
                    gcCollectionsDelta,
                    gcTimeDelta);

        } catch (OutOfMemoryError e) {
            return new BaselineResult(files.size(), 0, Duration.ZERO, 0, 0, true, "OutOfMemoryError", null, 0, 0);
        } catch (Exception e) {
            return new BaselineResult(files.size(), 0, Duration.ZERO, 0, 0, true, e.getMessage(), null, 0, 0);
        }
    }

    private void testSpecificProject() throws Exception {
        if (testLanguage == null) {
            System.err.println("test-project requires --language <lang>");
            System.err.println("Available languages: java, typescript, cpp");
            return;
        }

        if (testProject == null && testDirectory == null) {
            System.err.println("test-project requires either --project <name> or --directory <path>");
            System.err.println(
                    "Available projects: kafka, hibernate-orm, vscode, spring-framework, elasticsearch, intellij-community, openjdk, llvm, chromium");
            return;
        }

        if (testProject != null) {
            System.out.println("Testing project: " + testProject + " with language: " + testLanguage);
        } else {
            System.out.println("Testing directory: " + testDirectory + " with language: " + testLanguage);
        }
        System.out.println("Max files: " + maxFiles);

        // Debug: show discovered files
        var projectPath = getProjectPath(testProject != null ? testProject : "custom");
        var files = getProjectFiles(testProject != null ? testProject : "custom", testLanguage, maxFiles);
        System.out.println("Files discovered: " + files.size());
        files.stream()
                .limit(5)
                .forEach(file -> System.out.println("  " + file.absPath().toString() + " (exists: "
                        + file.absPath().toFile().exists() + ")"));

        try {
            var result = runProjectBaseline(testProject != null ? testProject : "custom", testLanguage);

            var target = testProject != null ? testProject : testDirectory.toString();
            System.out.printf("✅ SUCCESS: %s (%s)%n", target, testLanguage);
            System.out.printf("Files processed: %d%n", result.filesProcessed);
            System.out.printf("Analysis time: %.2f seconds%n", result.duration.toMillis() / 1000.0);
            System.out.printf("Peak memory: %.1f MB%n", result.peakMemoryMB);
            System.out.printf("Memory per file: %.1f KB%n", result.peakMemoryMB * 1024 / result.filesProcessed);

            if (result.basicStats != null) {
                System.out.println("Basic stats:");
                result.basicStats.forEach((k, v) -> System.out.printf("  %s: %d%n", k, v));
            }

        } catch (Exception e) {
            var target = testProject != null ? testProject : testDirectory.toString();
            System.err.printf("❌ FAILED: %s (%s) - %s%n", target, testLanguage, e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
        }
    }

    private void memoryStressTest() throws Exception {
        System.out.println("Running memory stress test...");
        var logEntries = new ArrayList<String>();
        logEntries.add("Running memory stress test...");

        // Timestamp for the output filename
        var timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));

        // Determine project and language based on CLI inputs or defaults
        var project = (testProject != null) ? testProject : "chromium";
        String language;
        if (testLanguage != null) {
            language = testLanguage;
        } else {
            var cfg = PROJECTS.get(project);
            language = (cfg != null && !cfg.languagePatterns.isEmpty())
                    ? cfg.languagePatterns.keySet().iterator().next()
                    : "cpp";
        }

        System.out.printf("Stressing project: %s, language: %s%n", project, language);
        if (testDirectory != null) {
            System.out.println("Directory: " + testDirectory);
        }

        // Build dynamic file count steps respecting --max-files upper bound
        var defaultSteps = new int[] {100, 500, 1000, 2000, 5000, 10000, 20000};
        var fileCountsList = new ArrayList<Integer>();
        for (int step : defaultSteps) {
            if (step <= maxFiles) {
                fileCountsList.add(step);
            }
        }
        // Ensure the user-requested maxFiles is included as the final step
        if (fileCountsList.isEmpty() || !fileCountsList.getLast().equals(maxFiles)) {
            fileCountsList.add(maxFiles);
        }

        for (int fileCount : fileCountsList) {
            var stepTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            String header = String.format("\n--- Testing with %d files [%s] ---\n", fileCount, stepTimestamp);
            System.out.print(header);
            logEntries.add(header);

            try {
                var oldMaxFiles = maxFiles;
                maxFiles = fileCount;

                var result = runProjectBaseline(project, language);

                // Calculate processing rate with safeguards and better precision
                double durationSeconds = result.duration.toMillis() / 1000.0;
                long durationMs = result.duration.toMillis();
                double processingRate = durationSeconds > 0.001
                        ? result.filesProcessed / durationSeconds
                        : 0.0; // Avoid division by very small numbers

                // Format timing with appropriate precision
                String timingInfo;
                if (durationMs < 10) {
                    timingInfo = String.format("%d ms", durationMs);
                } else if (durationMs < 1000) {
                    timingInfo = String.format("%.0f ms", (double) durationMs);
                } else {
                    timingInfo = String.format("%.2f seconds", durationSeconds);
                }

                // Format detailed results matching baseline report
                String detailedResults = String.format(
                        "✓ Success: %d files in %s, %.1f MB peak memory\n"
                                + "  Code units found: %d (functions, classes, variables, etc.)\n"
                                + "  Memory consumed: %.1f MB\n"
                                + "  Peak memory per file: %.1f KB (total peak ÷ file count)\n"
                                + "  Processing rate: %.2f files/second%s\n"
                                + "  Garbage collection: %d cycles, %d ms total\n",
                        result.filesProcessed,
                        timingInfo,
                        result.peakMemoryMB,
                        result.declarationsFound,
                        result.memoryDeltaMB,
                        result.peakMemoryMB * 1024 / result.filesProcessed,
                        processingRate,
                        durationMs < 10 ? " (likely cached results)" : "",
                        result.gcCollections,
                        result.gcTimeMs);

                System.out.print(detailedResults);
                logEntries.add(detailedResults);

                // Check for exponential growth
                if (fileCount > 1000 && result.peakMemoryMB > fileCount * 2.0) { // >2 MB per file indicates trouble
                    String warn = String.format(
                            "⚠ Warning: High memory usage detected (%.1f KB per file)\n",
                            result.peakMemoryMB * 1024 / fileCount);
                    System.out.print(warn);
                    logEntries.add(warn);
                }

                maxFiles = oldMaxFiles;

            } catch (OutOfMemoryError e) {
                String oom = String.format("❌ OutOfMemoryError at %d files - scalability limit found\n", fileCount);
                System.out.print(oom);
                logEntries.add(oom);
                break;
            }
        }

        // Persist the captured output
        try {
            Files.createDirectories(outputDir);
            String dirPart = (testDirectory != null)
                    ? "-" + testDirectory.getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_")
                    : "";
            Path logFile =
                    outputDir.resolve("memory-stress-" + project + "-" + language + dirPart + "-" + timestamp + ".txt");
            Files.writeString(logFile, String.join("", logEntries));
            System.out.println("Stress test results saved to: " + logFile.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to write stress test log: " + e.getMessage());
        }
    }

    private void multiLanguageAnalysis() throws Exception {
        System.out.println("Running multi-language analysis...");

        // Test Chromium with multiple languages
        var project = "chromium";
        var languages = List.of("cpp", "javascript", "python");

        for (String language : languages) {
            System.out.printf("\n--- Chromium %s Analysis ---\n", language.toUpperCase());

            try {
                var result = runProjectBaseline(project, language);
                System.out.printf(
                        "Files: %d, Time: %.2fs, Memory: %.1fMB\n",
                        result.filesProcessed, result.duration.toMillis() / 1000.0, result.peakMemoryMB);

            } catch (Exception e) {
                System.out.println("Failed: " + e.getMessage());
            }
        }
    }

    private List<ProjectFile> getProjectFiles(String projectName, String language, int maxFiles) throws IOException {
        var projectPath = getProjectPath(projectName);
        var config = PROJECTS.get(projectName);

        List<String> includePatterns;
        List<String> excludePatterns;

        if (config != null && testDirectory == null) {
            // Use predefined project configuration only if --directory is not specified
            includePatterns = config.languagePatterns.get(language);
            if (includePatterns == null) {
                throw new IllegalArgumentException("Language " + language + " not supported for " + projectName);
            }
            excludePatterns = config.excludePatterns;
        } else {
            // Use default patterns for unknown projects or when --directory is specified
            if (testDirectory == null && config == null) {
                throw new IllegalArgumentException(
                        "Unknown project: " + projectName + " (use --directory to specify a path)");
            }
            includePatterns = DEFAULT_LANGUAGE_PATTERNS.get(language.toLowerCase());
            if (includePatterns == null) {
                throw new IllegalArgumentException("No default include patterns for language " + language);
            }
            excludePatterns = List.of();
        }

        var pathMatchers = includePatterns.stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .collect(Collectors.toList());

        var excludeMatchers = excludePatterns.stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .collect(Collectors.toList());

        try (Stream<Path> paths = Files.walk(projectPath)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        Path relativePath = projectPath.relativize(path);
                        boolean included = pathMatchers.stream().anyMatch(matcher -> matcher.matches(relativePath));
                        boolean excluded = excludeMatchers.stream().anyMatch(matcher -> matcher.matches(relativePath));
                        return included && !excluded;
                    })
                    .limit(maxFiles)
                    .map(path -> new ProjectFile(projectPath, projectPath.relativize(path)))
                    .collect(Collectors.toList());
        }
    }

    private IAnalyzer createAnalyzer(Path projectRoot, String language, List<ProjectFile> files) {
        var project = new SimpleProject(projectRoot, parseLanguage(language), files);

        return switch (language.toLowerCase()) {
            case "cpp" -> {
                // Try to create CppTreeSitterAnalyzer if available
                try {
                    Class<?> cppAnalyzerClass = Class.forName("io.github.jbellis.brokk.analyzer.CppAnalyzer");
                    var constructor = cppAnalyzerClass.getConstructor(IProject.class, Set.class);
                    yield (IAnalyzer) constructor.newInstance(project, Set.of());
                } catch (Exception e) {
                    System.err.println("Warning: CppTreeSitterAnalyzer not available: " + e.getMessage());
                    yield null;
                }
            }
            case "java" -> {
                try {
                    Class<?> javaAnalyzerClass = Class.forName("io.github.jbellis.brokk.analyzer.JavaAnalyzer");
                    var constructor = javaAnalyzerClass.getConstructor(IProject.class);
                    yield (IAnalyzer) constructor.newInstance(project);
                } catch (Exception e) {
                    System.err.println("Warning: JavaAnalyzer not available: " + e.getMessage());
                    yield null;
                }
            }
            case "typescript" -> {
                try {
                    Class<?> tsAnalyzerClass = Class.forName("io.github.jbellis.brokk.analyzer.TypescriptAnalyzer");
                    var constructor = tsAnalyzerClass.getConstructor(IProject.class);
                    yield (IAnalyzer) constructor.newInstance(project);
                } catch (Exception e) {
                    System.err.println("Warning: TypescriptAnalyzer not available: " + e.getMessage());
                    yield null;
                }
            }
            case "javascript" -> {
                try {
                    Class<?> jsAnalyzerClass = Class.forName("io.github.jbellis.brokk.analyzer.JavascriptAnalyzer");
                    var constructor = jsAnalyzerClass.getConstructor(IProject.class);
                    yield (IAnalyzer) constructor.newInstance(project);
                } catch (Exception e) {
                    System.err.println("Warning: JavascriptAnalyzer not available: " + e.getMessage());
                    yield null;
                }
            }
            case "python" -> {
                try {
                    Class<?> pyAnalyzerClass = Class.forName("io.github.jbellis.brokk.analyzer.PythonAnalyzer");
                    var constructor = pyAnalyzerClass.getConstructor(IProject.class);
                    yield (IAnalyzer) constructor.newInstance(project);
                } catch (Exception e) {
                    System.err.println("Warning: PythonAnalyzer not available: " + e.getMessage());
                    yield null;
                }
            }
            default -> null;
        };
    }

    private Language parseLanguage(String languageStr) {
        return switch (languageStr.toLowerCase()) {
            case "cpp" -> {
                try {
                    // Try CPP_TREESITTER first, fall back to C_CPP
                    yield Languages.valueOf("CPP_TREESITTER");
                } catch (IllegalArgumentException e) {
                    yield Languages.C_CPP;
                }
            }
            case "java" -> Languages.JAVA;
            case "typescript" -> Languages.TYPESCRIPT;
            case "javascript" -> Languages.JAVASCRIPT;
            case "python" -> Languages.PYTHON;
            default -> null;
        };
    }

    private MemoryMonitor startMemoryMonitoring() {
        var monitor = new MemoryMonitor();
        monitor.start();
        return monitor;
    }

    /**
     * Samples heap memory every 100 ms and records the peak usage observed. Call {@link #stop()} to terminate the
     * sampler and {@link #getPeak()} to retrieve the peak heap used.
     */
    private static final class MemoryMonitor {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicLong peak = new AtomicLong(0);

        void start() {
            var t = new Thread(
                    () -> {
                        while (running.get()) {
                            long current = ManagementFactory.getMemoryMXBean()
                                    .getHeapMemoryUsage()
                                    .getUsed();
                            peak.updateAndGet(p -> Math.max(p, current));
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    },
                    "baseline-memory-monitor");
            t.setDaemon(true);
            t.start();
        }

        void stop() {
            running.set(false);
        }

        long getPeak() {
            return peak.get();
        }
    }

    private Map<String, Integer> getBasicStats(IAnalyzer analyzer) {
        // Return basic statistics - simplified for master compatibility
        var stats = new HashMap<String, Integer>();

        // Try to get cache size if method exists
        try {
            if (analyzer.getClass().getSimpleName().contains("TreeSitter")) {
                var method = analyzer.getClass().getMethod("getCacheStatistics");
                String cacheStats = (String) method.invoke(analyzer);
                stats.put("cacheInfo", cacheStats.length()); // Just store length as a basic metric
            }
        } catch (Exception e) {
            // Ignore - method doesn't exist or failed
        }

        return stats;
    }

    private void cloneProject(ProjectConfig config, Path targetPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "git", "clone", "--depth", "1", "--branch", config.branch, config.gitUrl, targetPath.toString());

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Failed to clone " + config.gitUrl);
        }
    }

    private Path getProjectPath(String projectName) {
        // 1) explicit --directory overrides everything
        if (testDirectory != null) {
            return testDirectory;
        }

        // 2) resolve against (possibly customised) projects base directory
        var baseCandidate =
                projectsBaseDir.resolve(projectName).toAbsolutePath().normalize();
        if (Files.exists(baseCandidate)) {
            return baseCandidate;
        }

        // 3) legacy fall-backs to keep existing scripts working
        var legacyCurrent =
                Paths.get(PROJECTS_DIR, projectName).toAbsolutePath().normalize();
        if (Files.exists(legacyCurrent)) {
            return legacyCurrent;
        }
        var legacyParent =
                Paths.get("..", PROJECTS_DIR, projectName).toAbsolutePath().normalize();
        if (Files.exists(legacyParent)) {
            return legacyParent;
        }

        // 4) default: return the candidate from step-2 (may not exist – caller will report)
        return baseCandidate;
    }

    private String parseArgumentsAndGetCommand(String[] args) {
        String command = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            // Check if this is a command (not starting with --)
            if (!arg.startsWith("--")) {
                command = arg;
                continue;
            }

            // Parse options
            switch (arg) {
                case "--max-files" -> {
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        maxFiles = Integer.parseInt(args[++i]);
                    }
                }
                case "--output" -> {
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        outputDir = Paths.get(args[++i]);
                    }
                }
                case "--project" -> {
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        testProject = args[++i];
                    }
                }
                case "--language" -> {
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        testLanguage = args[++i];
                    }
                }
                case "--projects-dir" -> {
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        projectsBaseDir = Paths.get(args[++i]).toAbsolutePath().normalize();
                    }
                }
                case "--directory" -> {
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        testDirectory = Paths.get(args[++i]).toAbsolutePath().normalize();
                    }
                }
                case "--memory-profile", "--memory" -> memoryProfiling = true;
                case "--stress-test" -> stressTest = true;
                case "--json" -> jsonOutput = true;
                case "--verbose" -> verbose = true;
                case "--show-details" -> showDetails = true;
                case "--cleanup" -> cleanupReports = true;
            }
        }

        if (command == null) {
            throw new IllegalArgumentException("No command specified");
        }

        return command;
    }

    private void printStartupBanner(String command) {
        System.out.println("=".repeat(80));
        System.out.println("TreeSitterRepoRunner starting (Simplified Version)");
        System.out.printf("Command       : %s%n", command);
        if (testProject != null) {
            System.out.printf("Project       : %s%n", testProject);
        }
        if (testLanguage != null) {
            System.out.printf("Language      : %s%n", testLanguage);
        }
        if (testDirectory != null) {
            System.out.printf("Directory     : %s%n", testDirectory.toAbsolutePath());
        }
        System.out.printf("Max files     : %d%n", maxFiles);
        System.out.printf("Memory profile: %s%n", memoryProfiling ? "ENABLED" : "disabled");
        System.out.println("Output dir    : " + outputDir.toAbsolutePath());
        System.out.println("=".repeat(80));
    }

    private void ensureOutputDirectory() throws IOException {
        Files.createDirectories(outputDir);
    }

    private void cleanupReportsDirectory() throws IOException {
        if (!Files.exists(outputDir)) {
            System.out.println("📁 Output directory doesn't exist: " + outputDir.toAbsolutePath());
            return;
        }

        var fileCount = 0;
        try (var files = Files.walk(outputDir)) {
            var filesToDelete = files.filter(Files::isRegularFile)
                    .filter(path -> {
                        var filename = path.getFileName().toString();
                        return filename.startsWith("baseline-") || filename.startsWith("memory-stress-");
                    })
                    .collect(Collectors.toList());

            for (var file : filesToDelete) {
                Files.delete(file);
                fileCount++;
                if (verbose) {
                    System.out.println("🗑️ Deleted: " + file.getFileName());
                }
            }
        }

        if (fileCount > 0) {
            System.out.printf("✅ Cleaned %d report files from %s%n", fileCount, outputDir.toAbsolutePath());
        } else {
            System.out.println("📭 No report files found to clean in " + outputDir.toAbsolutePath());
        }
    }

    private void printUsage() {
        System.out.println(
                """
            TreeSitterRepoRunner - TreeSitter Performance Baseline Measurement (Simplified)

            Usage: java TreeSitterRepoRunner [options] <command>

            Commands:
              setup-projects    Download/clone all test projects
              run-baselines     Execute full baseline suite
              test-project      Test specific project
              memory-stress     Memory stress test with increasing file counts (supports --project, --language, --directory)
              multi-language    Multi-language analysis on same project
              cleanup           Clean up report files from output directory

            Options:
              --max-files <n>   Maximum files to process (default: 1000)
              --output <path>   Output directory (default: baseline-results)
              --projects-dir <path>  Base directory for cloned projects (default: ../test-projects)
              --directory <path>     Custom directory to analyze (use absolute paths)
              --project <name>       Specific project to test
              --language <lang>      Language to analyze (java, typescript, cpp, etc.)
              --memory-profile  Enable detailed memory profiling
              --stress-test     Run until OutOfMemoryError
              --json            Output in JSON format
              --verbose         Enable verbose logging
              --show-details    Show symbols found in each file
              --cleanup         Clean up reports before running command
            """);
    }

    private void printBaselineSummary(BaselineResults results) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("BASELINE SUMMARY");
        System.out.println("=".repeat(60));

        // Implementation for summary printing
        System.out.println("Baseline execution completed. Check output files for details.");
    }

    // Helper classes
    private record ProjectConfig(
            String gitUrl, String branch, Map<String, List<String>> languagePatterns, List<String> excludePatterns) {}

    private record BaselineResult(
            int filesProcessed,
            int declarationsFound,
            Duration duration,
            double memoryDeltaMB,
            double peakMemoryMB,
            boolean failed,
            String failureReason,
            Map<String, Integer> basicStats,
            long gcCollections,
            long gcTimeMs) {}

    private static class BaselineResults {
        private final Map<String, Map<String, BaselineResult>> results = new HashMap<>();
        private final List<String> failures = new ArrayList<>();

        void addResult(String project, String language, BaselineResult result) {
            results.computeIfAbsent(project, k -> new HashMap<>()).put(language, result);
        }

        void recordFailure(String project, String language, String reason) {
            failures.add(project + ":" + language + " - " + reason);
        }

        void recordOOM(String project, String language, int fileCount) {
            failures.add(project + ":" + language + " - OOM at " + fileCount + " files");
        }

        void recordError(String project, String language, String error) {
            failures.add(project + ":" + language + " - " + error);
        }

        void saveIncrementalResult(
                String project, String language, BaselineResult result, Path baseOutputDir, String timestamp)
                throws IOException {
            // Simplified incremental saving
            var summaryFile = baseOutputDir.resolve("baseline-" + timestamp + "-summary.txt");
            appendToTextSummary(project, language, result, summaryFile);
        }

        private void appendToTextSummary(String project, String language, BaselineResult result, Path file)
                throws IOException {
            boolean fileExists = Files.exists(file);

            var header = fileExists
                    ? ""
                    : """
                    TreeSitter Performance Baseline Summary (Incremental - Simplified)
                    ==============================================================
                    Generated: %s
                    JVM Heap Max: %dMB
                    Processors: %d

                    RESULTS (as completed):
                    ======================
                    %-20s %-12s %-8s %-12s %-12s %-15s %-6s %-20s
                    %s
                    """
                            .formatted(
                                    LocalDateTime.now(),
                                    Runtime.getRuntime().maxMemory() / (1024 * 1024),
                                    Runtime.getRuntime().availableProcessors(),
                                    "PROJECT",
                                    "LANGUAGE",
                                    "FILES",
                                    "TIME(sec)",
                                    "MEMORY(MB)",
                                    "MB/FILE",
                                    "-".repeat(100));

            var status = result.failed ? "YES" : "NO";
            var reason = result.failed && result.failureReason != null ? result.failureReason : "";
            if (reason.length() > 20) reason = reason.substring(0, 17) + "...";

            var resultLine = String.format(
                    "%-20s %-12s %-8d %-12.1f %-12.1f %-15.2f %-6s %-20s\n",
                    project,
                    language,
                    result.filesProcessed,
                    result.duration.toMillis() / 1000.0,
                    result.peakMemoryMB,
                    result.peakMemoryMB / result.filesProcessed,
                    status,
                    reason);

            Files.writeString(
                    file, header + resultLine, fileExists ? StandardOpenOption.APPEND : StandardOpenOption.CREATE);
        }

        void saveToFile(Path file) throws IOException {
            var resultsJson =
                    results.entrySet().stream().map(this::formatProjectJson).collect(Collectors.joining(",\n"));

            var json =
                    """
                    {
                      "timestamp": "%s",
                      "jvm_settings": {
                        "heap_max": "%dMB",
                        "processors": %d
                      },
                      "results": {
                    %s
                      },
                      "failures": [
                    %s
                      ]
                    }
                    """
                            .formatted(
                                    LocalDateTime.now(),
                                    Runtime.getRuntime().maxMemory() / (1024 * 1024),
                                    Runtime.getRuntime().availableProcessors(),
                                    resultsJson,
                                    formatFailuresJson());
            Files.writeString(file, json);
        }

        private String formatProjectJson(Map.Entry<String, Map<String, BaselineResult>> projectEntry) {
            var languageResults = projectEntry.getValue().entrySet().stream()
                    .map(this::formatLanguageJson)
                    .collect(Collectors.joining(",\n"));

            return """
                      "%s": {
                    %s
                      }"""
                    .formatted(projectEntry.getKey(), languageResults);
        }

        private String formatLanguageJson(Map.Entry<String, BaselineResult> langEntry) {
            var result = langEntry.getValue();
            var failureReason = result.failureReason != null
                    ? ",\n        \"failure_reason\": \"" + result.failureReason.replace("\"", "\\\"") + "\""
                    : "";

            return """
                        "%s": {
                          "files_processed": %d,
                          "declarations_found": %d,
                          "analysis_time_ms": %d,
                          "analysis_time_seconds": %.2f,
                          "memory_delta_mb": %.1f,
                          "peak_memory_mb": %.1f,
                          "memory_per_file_kb": %.1f,
                          "files_per_second": %.2f,
                          "gc_collections": %d,
                          "gc_time_ms": %d,
                          "failed": %s%s
                        }"""
                    .formatted(
                            langEntry.getKey(),
                            result.filesProcessed,
                            result.declarationsFound,
                            result.duration.toMillis(),
                            result.duration.toMillis() / 1000.0,
                            result.memoryDeltaMB,
                            result.peakMemoryMB,
                            result.peakMemoryMB * 1024 / result.filesProcessed,
                            result.filesProcessed / (result.duration.toMillis() / 1000.0),
                            result.gcCollections(),
                            result.gcTimeMs(),
                            result.failed,
                            failureReason);
        }

        private String formatFailuresJson() {
            return failures.stream()
                    .map(failure -> "    \"" + failure.replace("\"", "\\\"") + "\"")
                    .collect(Collectors.joining(",\n"));
        }

        void saveToCsv(Path file) throws IOException {
            var header =
                    "project,language,files_processed,declarations_found,analysis_time_seconds,peak_memory_mb,memory_per_file_kb,files_per_second,gc_collections,gc_time_ms,failed,failure_reason";

            var csvBody = results.entrySet().stream()
                    .flatMap(projectEntry -> projectEntry.getValue().entrySet().stream()
                            .map(langEntry -> formatCsvRow(projectEntry.getKey(), langEntry)))
                    .collect(Collectors.joining("\n"));

            Files.writeString(file, header + "\n" + csvBody + "\n");
        }

        private String formatCsvRow(String projectName, Map.Entry<String, BaselineResult> langEntry) {
            var result = langEntry.getValue();
            var failureReason =
                    result.failureReason != null ? "\"" + result.failureReason.replace("\"", "\"\"") + "\"" : "";

            return String.join(
                    ",",
                    projectName,
                    langEntry.getKey(),
                    String.valueOf(result.filesProcessed),
                    String.valueOf(result.declarationsFound),
                    String.format("%.2f", result.duration.toMillis() / 1000.0),
                    String.format("%.1f", result.peakMemoryMB),
                    String.format("%.1f", result.peakMemoryMB * 1024 / result.filesProcessed),
                    String.format("%.2f", result.filesProcessed / (result.duration.toMillis() / 1000.0)),
                    String.valueOf(result.gcCollections()),
                    String.valueOf(result.gcTimeMs()),
                    String.valueOf(result.failed),
                    failureReason);
        }

        void saveTextSummary(Path file) throws IOException {
            var successfulResults = results.entrySet().stream()
                    .flatMap(projectEntry -> projectEntry.getValue().entrySet().stream()
                            .filter(langEntry -> !langEntry.getValue().failed)
                            .map(langEntry -> formatSuccessfulRow(projectEntry.getKey(), langEntry)))
                    .collect(Collectors.toList());

            var totalSuccessful = successfulResults.size();
            var totalFailed = (int) results.values().stream()
                    .flatMapToInt(langMap -> langMap.values().stream().mapToInt(r -> r.failed ? 1 : 0))
                    .sum();

            var failedSection = failures.isEmpty()
                    ? ""
                    : "\nFAILED ANALYSES:\n" + "===============\n"
                            + failures.stream().map(failure -> "❌ " + failure).collect(Collectors.joining("\n"))
                            + "\n";

            var summary =
                    """
                    TreeSitter Performance Baseline Summary (Simplified)
                    ==================================================
                    Generated: %s
                    JVM Heap Max: %dMB
                    Processors: %d

                    SUCCESSFUL ANALYSES:
                    ===================
                    %-20s %-12s %-8s %-12s %-12s %-15s
                    %s
                    %s%s
                    SUMMARY STATISTICS:
                    ==================
                    Total successful: %d
                    Total failed: %d
                    Success rate: %.1f%%
                    """
                            .formatted(
                                    LocalDateTime.now(),
                                    Runtime.getRuntime().maxMemory() / (1024 * 1024),
                                    Runtime.getRuntime().availableProcessors(),
                                    "PROJECT",
                                    "LANGUAGE",
                                    "FILES",
                                    "TIME(sec)",
                                    "MEMORY(MB)",
                                    "MB/FILE",
                                    "-".repeat(80),
                                    String.join("\n", successfulResults),
                                    failedSection,
                                    totalSuccessful,
                                    totalFailed,
                                    100.0 * totalSuccessful / (totalSuccessful + totalFailed));

            Files.writeString(file, summary);
        }

        private String formatSuccessfulRow(String projectName, Map.Entry<String, BaselineResult> langEntry) {
            var result = langEntry.getValue();
            return String.format(
                    "%-20s %-12s %-8d %-12.1f %-12.1f %-15.2f",
                    projectName,
                    langEntry.getKey(),
                    result.filesProcessed,
                    result.duration.toMillis() / 1000.0,
                    result.peakMemoryMB,
                    result.peakMemoryMB / result.filesProcessed);
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
    }
}
