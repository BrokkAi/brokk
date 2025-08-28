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
 * Maximum files to process (default: 1000) --output <path> Output directory for results (default: baseline-results)
 * --memory-profile Enable detailed memory profiling --stress-test Run until OutOfMemoryError to find limits --json
 * Output results in JSON format --verbose Enable verbose logging --show-details Show symbols found in each file
 */
public class TreeSitterRepoRunner {

    private static final String PROJECTS_DIR = "../test-projects";
    private static final String DEFAULT_OUTPUT_DIR = "baseline-results";

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
                        List.of("**/test/**", "build/**", "**/target/**")));
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
            ensureOutputDirectory();
            printStartupBanner(command);

            switch (command) {
                case "setup-projects" -> setupProjects();
                case "run-baselines" -> runFullBaselines();
                case "test-project" -> testSpecificProject();
                case "memory-stress" -> memoryStressTest();
                case "multi-language" -> multiLanguageAnalysis();
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
            String projectName = entry.getKey();
            ProjectConfig config = entry.getValue();

            Path projectPath = projectsBaseDir.resolve(projectName);

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
            var declarations = new ArrayList<CodeUnit>();
            for (var file : files) {
                // This triggers actual TreeSitter parsing for each file
                var fileDeclarations = analyzer.getDeclarationsInFile(file);
                declarations.addAll(fileDeclarations);

                // Show detailed symbol information if requested
                if (showDetails) {
                    System.out.printf("📄 %s (%d symbols):%n", file.getRelPath(), fileDeclarations.size());
                    for (var declaration : fileDeclarations) {
                        var name = declaration.shortName();
                        System.out.printf("  - %s: %s%n", declaration.kind(), name);
                    }
                    System.out.println();
                }
            }

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
        var log = new StringBuilder();
        log.append("Running memory stress test...\n");

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
        int[] defaultSteps = {100, 500, 1000, 2000, 5000, 10000, 20000};
        List<Integer> fileCountsList = new ArrayList<>();
        for (int step : defaultSteps) {
            if (step <= maxFiles) {
                fileCountsList.add(step);
            }
        }
        // Ensure the user-requested maxFiles is included as the final step
        if (fileCountsList.isEmpty() || fileCountsList.get(fileCountsList.size() - 1) != maxFiles) {
            fileCountsList.add(maxFiles);
        }

        for (int fileCount : fileCountsList) {
            var stepTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            String header = String.format("\n--- Testing with %d files [%s] ---\n", fileCount, stepTimestamp);
            System.out.print(header);
            log.append(header);

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
                log.append(detailedResults);

                // Check for exponential growth
                if (fileCount > 1000 && result.peakMemoryMB > fileCount * 2.0) { // >2 MB per file indicates trouble
                    String warn = String.format(
                            "⚠ Warning: High memory usage detected (%.1f KB per file)\n",
                            result.peakMemoryMB * 1024 / fileCount);
                    System.out.print(warn);
                    log.append(warn);
                }

                maxFiles = oldMaxFiles;

            } catch (OutOfMemoryError e) {
                String oom = String.format("❌ OutOfMemoryError at %d files - scalability limit found\n", fileCount);
                System.out.print(oom);
                log.append(oom);
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
            Files.writeString(logFile, log.toString());
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
                    Class<?> cppAnalyzerClass = Class.forName("io.github.jbellis.brokk.analyzer.CppTreeSitterAnalyzer");
                    var constructor = cppAnalyzerClass.getConstructor(IProject.class, Set.class);
                    yield (IAnalyzer) constructor.newInstance(project, Set.of());
                } catch (Exception e) {
                    System.err.println("Warning: CppTreeSitterAnalyzer not available: " + e.getMessage());
                    yield null;
                }
            }
            case "java" -> {
                // Use JavaAnalyzer.create() factory method as per master branch
                yield JavaAnalyzer.create(project);
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
                    yield Language.valueOf("CPP_TREESITTER");
                } catch (IllegalArgumentException e) {
                    yield Language.C_CPP;
                }
            }
            case "java" -> Language.JAVA;
            case "typescript" -> Language.TYPESCRIPT;
            case "javascript" -> Language.JAVASCRIPT;
            case "python" -> Language.PYTHON;
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
            Thread t = new Thread(
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
            var summary = new StringBuilder();

            // Add header if file doesn't exist
            if (!fileExists) {
                summary.append("TreeSitter Performance Baseline Summary (Incremental - Simplified)\n");
                summary.append("==============================================================\n");
                summary.append("Generated: ").append(LocalDateTime.now()).append("\n");
                summary.append("JVM Heap Max: ")
                        .append(Runtime.getRuntime().maxMemory() / (1024 * 1024))
                        .append("MB\n");
                summary.append("Processors: ")
                        .append(Runtime.getRuntime().availableProcessors())
                        .append("\n\n");
                summary.append("RESULTS (as completed):\n");
                summary.append("======================\n");
                summary.append(String.format(
                        "%-20s %-12s %-8s %-12s %-12s %-15s %-6s %-20s\n",
                        "PROJECT", "LANGUAGE", "FILES", "TIME(sec)", "MEMORY(MB)", "MB/FILE", "FAILED", "REASON"));
                summary.append("-".repeat(100)).append("\n");
            }

            // Add result line
            String status = result.failed ? "YES" : "NO";
            String reason = result.failed && result.failureReason != null ? result.failureReason : "";
            if (reason.length() > 20) reason = reason.substring(0, 17) + "...";

            summary.append(String.format(
                    "%-20s %-12s %-8d %-12.1f %-12.1f %-15.2f %-6s %-20s\n",
                    project,
                    language,
                    result.filesProcessed,
                    result.duration.toMillis() / 1000.0,
                    result.peakMemoryMB,
                    result.peakMemoryMB / result.filesProcessed,
                    status,
                    reason));

            // Append to file
            Files.writeString(
                    file,
                    summary.toString(),
                    fileExists ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
        }

        void saveToFile(Path file) throws IOException {
            var json = new StringBuilder();
            json.append("{\n");
            json.append("  \"timestamp\": \"").append(LocalDateTime.now()).append("\",\n");
            json.append("  \"jvm_settings\": {\n");
            json.append("    \"heap_max\": \"")
                    .append(Runtime.getRuntime().maxMemory() / (1024 * 1024))
                    .append("MB\",\n");
            json.append("    \"processors\": ")
                    .append(Runtime.getRuntime().availableProcessors())
                    .append("\n");
            json.append("  },\n");
            json.append("  \"results\": {\n");

            boolean firstProject = true;
            for (var projectEntry : results.entrySet()) {
                if (!firstProject) json.append(",\n");
                firstProject = false;

                json.append("    \"").append(projectEntry.getKey()).append("\": {\n");

                boolean firstLang = true;
                for (var langEntry : projectEntry.getValue().entrySet()) {
                    if (!firstLang) json.append(",\n");
                    firstLang = false;

                    var result = langEntry.getValue();
                    json.append("      \"").append(langEntry.getKey()).append("\": {\n");
                    json.append("        \"files_processed\": ")
                            .append(result.filesProcessed)
                            .append(",\n");
                    json.append("        \"declarations_found\": ")
                            .append(result.declarationsFound)
                            .append(",\n");
                    json.append("        \"analysis_time_ms\": ")
                            .append(result.duration.toMillis())
                            .append(",\n");
                    json.append("        \"analysis_time_seconds\": ")
                            .append(String.format("%.2f", result.duration.toMillis() / 1000.0))
                            .append(",\n");
                    json.append("        \"memory_delta_mb\": ")
                            .append(String.format("%.1f", result.memoryDeltaMB))
                            .append(",\n");
                    json.append("        \"peak_memory_mb\": ")
                            .append(String.format("%.1f", result.peakMemoryMB))
                            .append(",\n");
                    json.append("        \"memory_per_file_kb\": ")
                            .append(String.format("%.1f", result.peakMemoryMB * 1024 / result.filesProcessed))
                            .append(",\n");
                    json.append("        \"files_per_second\": ")
                            .append(String.format(
                                    "%.2f", result.filesProcessed / (result.duration.toMillis() / 1000.0)))
                            .append(",\n");
                    json.append("        \"gc_collections\": ")
                            .append(result.gcCollections())
                            .append(",\n");
                    json.append("        \"gc_time_ms\": ")
                            .append(result.gcTimeMs())
                            .append(",\n");
                    json.append("        \"failed\": ").append(result.failed);
                    if (result.failureReason != null) {
                        json.append(",\n        \"failure_reason\": \"")
                                .append(result.failureReason.replace("\"", "\\\""))
                                .append("\"");
                    }
                    json.append("\n      }");
                }
                json.append("\n    }");
            }

            json.append("\n  },\n");
            json.append("  \"failures\": [\n");
            for (int i = 0; i < failures.size(); i++) {
                if (i > 0) json.append(",\n");
                json.append("    \"")
                        .append(failures.get(i).replace("\"", "\\\""))
                        .append("\"");
            }
            json.append("\n  ]\n");
            json.append("}\n");

            Files.writeString(file, json.toString());
        }

        void saveToCsv(Path file) throws IOException {
            var csv = new StringBuilder();
            csv.append(
                    "project,language,files_processed,declarations_found,analysis_time_seconds,peak_memory_mb,memory_per_file_kb,files_per_second,gc_collections,gc_time_ms,failed,failure_reason\n");

            for (var projectEntry : results.entrySet()) {
                for (var langEntry : projectEntry.getValue().entrySet()) {
                    var result = langEntry.getValue();
                    csv.append(projectEntry.getKey()).append(",");
                    csv.append(langEntry.getKey()).append(",");
                    csv.append(result.filesProcessed).append(",");
                    csv.append(result.declarationsFound).append(",");
                    csv.append(String.format("%.2f", result.duration.toMillis() / 1000.0))
                            .append(",");
                    csv.append(String.format("%.1f", result.peakMemoryMB)).append(",");
                    csv.append(String.format("%.1f", result.peakMemoryMB * 1024 / result.filesProcessed))
                            .append(",");
                    csv.append(String.format("%.2f", result.filesProcessed / (result.duration.toMillis() / 1000.0)))
                            .append(",");
                    csv.append(result.gcCollections()).append(",");
                    csv.append(result.gcTimeMs()).append(",");
                    csv.append(result.failed).append(",");
                    csv.append(
                            result.failureReason != null
                                    ? "\"" + result.failureReason.replace("\"", "\"\"") + "\""
                                    : "");
                    csv.append("\n");
                }
            }

            Files.writeString(file, csv.toString());
        }

        void saveTextSummary(Path file) throws IOException {
            var summary = new StringBuilder();
            summary.append("TreeSitter Performance Baseline Summary (Simplified)\n");
            summary.append("==================================================\n");
            summary.append("Generated: ").append(LocalDateTime.now()).append("\n");
            summary.append("JVM Heap Max: ")
                    .append(Runtime.getRuntime().maxMemory() / (1024 * 1024))
                    .append("MB\n");
            summary.append("Processors: ")
                    .append(Runtime.getRuntime().availableProcessors())
                    .append("\n\n");

            // Success summary
            int totalSuccessful = 0;
            int totalFailed = 0;

            summary.append("SUCCESSFUL ANALYSES:\n");
            summary.append("===================\n");
            summary.append(String.format(
                    "%-20s %-12s %-8s %-12s %-12s %-15s\n",
                    "PROJECT", "LANGUAGE", "FILES", "TIME(sec)", "MEMORY(MB)", "MB/FILE"));
            summary.append("-".repeat(80)).append("\n");

            for (var projectEntry : results.entrySet()) {
                for (var langEntry : projectEntry.getValue().entrySet()) {
                    var result = langEntry.getValue();
                    if (!result.failed) {
                        totalSuccessful++;
                        summary.append(String.format(
                                "%-20s %-12s %-8d %-12.1f %-12.1f %-15.2f\n",
                                projectEntry.getKey(),
                                langEntry.getKey(),
                                result.filesProcessed,
                                result.duration.toMillis() / 1000.0,
                                result.peakMemoryMB,
                                result.peakMemoryMB / result.filesProcessed));
                    } else {
                        totalFailed++;
                    }
                }
            }

            if (!failures.isEmpty()) {
                summary.append("\nFAILED ANALYSES:\n");
                summary.append("===============\n");
                for (String failure : failures) {
                    summary.append("❌ ").append(failure).append("\n");
                }
            }

            summary.append("\nSUMMARY STATISTICS:\n");
            summary.append("==================\n");
            summary.append("Total successful: ").append(totalSuccessful).append("\n");
            summary.append("Total failed: ").append(totalFailed).append("\n");
            summary.append("Success rate: ")
                    .append(String.format("%.1f%%", 100.0 * totalSuccessful / (totalSuccessful + totalFailed)))
                    .append("\n");

            Files.writeString(file, summary.toString());
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
