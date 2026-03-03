package ai.brokk.util;

import static ai.brokk.project.FileFilteringService.toUnixPath;

import ai.brokk.AnalyzerUtil;
import ai.brokk.ContextManager;
import ai.brokk.IContextManager;
import ai.brokk.LlmOutputMeta;
import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.project.IProject;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import dev.langchain4j.data.message.ChatMessageType;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

@org.jspecify.annotations.NullMarked
public class BuildTools {
    private static final Logger logger = LogManager.getLogger(BuildTools.class);

    /** Determine the best verification command using the provided Context. */
    @Blocking
    public static @Nullable String determineVerificationCommand(Context ctx) throws InterruptedException {
        return determineVerificationCommand(ctx, null, null);
    }

    /** Determine the best verification command using the provided Context and an optional override. */
    @Blocking
    public static @Nullable String determineVerificationCommand(Context ctx, @Nullable BuildDetails override)
            throws InterruptedException {
        return determineVerificationCommand(ctx, override, null);
    }

    /**
     * Determine the best verification command using the provided Context and an optional override.
     *
     * <p>If {@code testFilesOverride} is non-null and non-empty, it overrides workspace-derived test selection.
     */
    @Blocking
    public static @Nullable String determineVerificationCommand(
            Context ctx, @Nullable BuildDetails override, @Nullable Collection<ProjectFile> testFilesOverride)
            throws InterruptedException {
        var cm = ctx.getContextManager();
        BuildDetails details = override != null ? override : cm.getProject().awaitBuildDetails();

        if (details.equals(BuildDetails.EMPTY)) {
            logger.warn("No build details available, cannot determine verification command.");
            return null;
        }

        IProject.CodeAgentTestScope testScope = cm.getProject().getCodeAgentTestScope();
        if (testScope == IProject.CodeAgentTestScope.ALL) {
            String cmd = System.getenv("BRK_TESTALL_CMD") != null
                    ? System.getenv("BRK_TESTALL_CMD")
                    : details.testAllCommand();
            return interpolateCommandWithPythonVersion(cmd, cm.getProject().getRoot());
        }

        if (testFilesOverride != null && !testFilesOverride.isEmpty()) {
            return getBuildLintSomeCommand(cm, details, testFilesOverride);
        }

        var projectFilesFromEditableOrReadOnly = ctx.allFragments()
                .filter(f -> f.getType().isPath())
                .flatMap(fragment -> fragment.sourceFiles().join().stream());

        var projectFilesFromSkeletons = ctx.allFragments()
                .filter(vf -> vf.getType() == ContextFragment.FragmentType.SKELETON)
                .flatMap(skeletonFragment -> skeletonFragment.sourceFiles().join().stream());

        var workspaceFiles = Stream.concat(projectFilesFromEditableOrReadOnly, projectFilesFromSkeletons)
                .collect(Collectors.toSet());

        var analyzer = cm.getAnalyzer();
        var workspaceTestFiles = workspaceFiles.stream()
                .filter(f -> ContextManager.isTestFile(f, analyzer))
                .toList();

        if (workspaceTestFiles.isEmpty()) {
            return interpolateCommandWithPythonVersion(
                    details.buildLintCommand(), cm.getProject().getRoot());
        }

        return getBuildLintSomeCommand(cm, details, workspaceTestFiles);
    }

    public static String getBuildLintSomeCommand(
            IContextManager cm, BuildDetails details, Collection<ProjectFile> workspaceTestFiles)
            throws InterruptedException {
        return getBuildLintSomeCommand(cm, details, workspaceTestFiles, null);
    }

    public static CompletableFuture<@Nullable String> determineVerificationCommandAsync(ContextManager cm) {
        return cm.submitBackgroundTask(
                "Determine build verification command", () -> determineVerificationCommand(cm.liveContext()));
    }

    public static String getBuildLintSomeCommand(
            IContextManager cm,
            BuildDetails details,
            Collection<ProjectFile> workspaceTestFiles,
            @Nullable String pythonVersionOverride)
            throws InterruptedException {

        String testSomeTemplate = System.getenv("BRK_TESTSOME_CMD") != null
                ? System.getenv("BRK_TESTSOME_CMD")
                : details.testSomeCommand();

        if (testSomeTemplate.isBlank()) {
            return details.buildLintCommand();
        }

        final Path projectRoot = cm.getProject().getRoot();
        String pythonVersion =
                pythonVersionOverride != null ? pythonVersionOverride : getPythonVersionForProject(projectRoot);

        IAnalyzer analyzer = cm.getAnalyzer();
        Map<String, Object> context = new HashMap<>();
        context.put("pyver", pythonVersion == null ? "" : pythonVersion);

        // Always calculate all potential lists to support mixed templates
        // 1. Packages
        Path anchor = detectModuleAnchor(projectRoot, details).orElse(null);
        List<String> packages = workspaceTestFiles.stream()
                .map(pf -> toPythonModuleLabel(projectRoot, anchor, Path.of(pf.toString())))
                .filter(s -> !s.isBlank())
                .distinct()
                .sorted()
                .toList();

        if (packages.isEmpty() && !analyzer.isEmpty()) {
            packages = analyzer.getTestModules(workspaceTestFiles);
        }
        context.put("packages", MustacheTemplates.toStringElementList(packages));

        // 2. Files
        context.put(
                "files",
                MustacheTemplates.toStringElementList(
                        workspaceTestFiles.stream().map(ProjectFile::toString).toList()));

        // 3. Classes
        List<String> fqClasses = List.of();
        List<String> classes = List.of();
        if (!analyzer.isEmpty()) {
            var codeUnits = AnalyzerUtil.testFilesToCodeUnits(analyzer, workspaceTestFiles);
            fqClasses = codeUnits.stream().map(CodeUnit::fqName).sorted().toList();
            classes = codeUnits.stream().map(CodeUnit::identifier).sorted().toList();
        }
        context.put("fqclasses", MustacheTemplates.toStringElementList(fqClasses));
        context.put("classes", MustacheTemplates.toStringElementList(classes));

        // Perform multi-variable interpolation
        MustacheFactory mf = new DefaultMustacheFactory();
        Mustache mustache = mf.compile(new StringReader(testSomeTemplate), "dynamic_template");

        StringWriter writer = new StringWriter();
        mustache.execute(writer, context);
        String result = writer.toString();

        // If the result is blank or identical to the template, it means no sections matched
        // or no targets were found; fall back to build/lint command.
        if (result.isBlank() || result.equals(testSomeTemplate)) {
            return details.buildLintCommand();
        }

        return result;
    }

    private static Optional<Path> detectModuleAnchor(Path projectRoot, BuildDetails details) {
        String testAll = details.testAllCommand();
        String testSome = details.testSomeCommand();

        Optional<Path> fromRunner = extractRunnerAnchorFromCommands(projectRoot, List.of(testAll, testSome));
        if (fromRunner.isPresent()) return fromRunner;

        Path tests = projectRoot.resolve("tests");
        if (Files.isDirectory(tests)) return Optional.of(tests);

        return Optional.empty();
    }

    public static Optional<Path> extractRunnerAnchorFromCommands(Path projectRoot, List<String> commands) {
        // Regex to match either:
        // 1. Quoted strings: "..." or '...'
        // 2. Non-whitespace strings, excluding shell operators like ;, &, |
        Pattern pattern = Pattern.compile("\"([^\"]*)\"|'([^']*)'|([^\\s&&[^;&|]]+)");

        for (String cmd : commands) {
            if (cmd.isBlank()) continue;

            var matcher = pattern.matcher(cmd);
            while (matcher.find()) {
                // Determine which group matched (1=double-quote, 2=single-quote, 3=unquoted)
                String token = matcher.group(1) != null
                        ? matcher.group(1)
                        : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);

                if (token == null || token.isBlank()) continue;

                // Filter out flags and assignments
                if (token.startsWith("-") || token.contains("=")) continue;

                // We are looking for Python test runners
                if (!token.endsWith(".py")) continue;

                Path candidate = projectRoot.resolve(token).normalize();
                if (!Files.exists(candidate)) {
                    Path p = Path.of(token);
                    if (p.isAbsolute() && Files.exists(p)) {
                        candidate = p.normalize();
                    }
                }

                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    Path parent = candidate.getParent();
                    if (parent != null && Files.isDirectory(parent)) {
                        return Optional.of(parent);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static @Nullable String getPythonVersionForProject(Path projectRoot) {
        try {
            return new EnvironmentPython(projectRoot).getPythonVersion();
        } catch (Exception e) {
            logger.debug("Unable to determine Python version for project", e);
            return null;
        }
    }

    private static String toPythonModuleLabel(Path projectRoot, @Nullable Path anchor, Path filePath) {
        Path abs = projectRoot.resolve(filePath).normalize();
        Path base = anchor;
        if (base == null || !abs.startsWith(base)) {
            base = inferImportRoot(abs).orElse(null);
        }
        if (base == null) return "";
        Path rel;
        try {
            rel = base.relativize(abs);
        } catch (IllegalArgumentException e) {
            return "";
        }
        String s = toUnixPath(rel);
        if (s.endsWith(".py")) s = s.substring(0, s.length() - 3);
        if (s.endsWith("/__init__")) s = s.substring(0, s.length() - "/__init__".length());
        while (s.startsWith("/")) s = s.substring(1);
        String dotted = s.replace('/', '.');
        while (dotted.startsWith(".")) dotted = dotted.substring(1);
        return dotted;
    }

    private static Optional<Path> inferImportRoot(Path absFile) {
        if (!Files.isRegularFile(absFile)) {
            return Optional.empty();
        }
        Path p = absFile.getParent();
        Path lastWithInit = null;
        // Search upwards for the top-most package directory containing __init__.py
        while (p != null && Files.isRegularFile(p.resolve("__init__.py"))) {
            lastWithInit = p;
            p = p.getParent();
        }
        return Optional.ofNullable(
                Objects.requireNonNullElse(lastWithInit, absFile).getParent());
    }

    private static String interpolateCommandWithPythonVersion(String command, Path projectRoot) {
        if (command.isEmpty()) return command;
        if (System.getenv("BRK_TESTALL_CMD") != null) command = System.getenv("BRK_TESTALL_CMD");
        String pythonVersion = getPythonVersionForProject(projectRoot);
        return interpolateMustacheTemplate(command, List.of(), "unused", pythonVersion);
    }

    public static String interpolateMustacheTemplate(String template, List<String> items, String listKey) {
        return interpolateMustacheTemplate(template, items, listKey, null);
    }

    public static String interpolateMustacheTemplate(
            String template, List<String> items, String listKey, @Nullable String pythonVersion) {
        if (template.isEmpty()) return "";
        // Use unified interpolation logic compatible with BuildAgent
        MustacheFactory mf = new DefaultMustacheFactory();
        Mustache mustache = mf.compile(new StringReader(template), "dynamic_template");
        Map<String, Object> context = new HashMap<>();
        context.put(listKey, MustacheTemplates.toStringElementList(items));
        context.put("pyver", pythonVersion == null ? "" : pythonVersion);
        StringWriter writer = new StringWriter();
        mustache.execute(writer, context);
        return writer.toString();
    }

    @Blocking
    public static String runVerification(IContextManager cm) throws InterruptedException {
        return runVerification(cm, null);
    }

    @Blocking
    public static String runVerification(IContextManager cm, @Nullable BuildDetails override)
            throws InterruptedException {
        var interrupted = new AtomicReference<InterruptedException>(null);
        var updated = cm.pushContext(ctx -> {
            try {
                return runVerification(ctx, override);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted.set(e);
                return ctx;
            }
        });
        var ie = interrupted.get();
        if (ie != null) throw ie;
        return updated.getBuildError();
    }

    @Blocking
    public static Context runVerification(Context ctx) throws InterruptedException {
        return runVerification(ctx, null, null);
    }

    @Blocking
    public static Context runVerification(Context ctx, @Nullable BuildDetails override) throws InterruptedException {
        return runVerification(ctx, override, null);
    }

    @Blocking
    public static Context runVerification(
            Context ctx, @Nullable BuildDetails override, @Nullable Collection<ProjectFile> testFilesOverride)
            throws InterruptedException {
        var cm = ctx.getContextManager();
        var io = cm.getIo();
        var verificationCommand = determineVerificationCommand(ctx, override, testFilesOverride);
        if (verificationCommand == null || verificationCommand.isBlank()) {
            io.llmOutput(
                    "\nNo verification command specified, skipping build/check.",
                    ChatMessageType.CUSTOM,
                    LlmOutputMeta.DEFAULT);
            return ctx;
        }
        boolean noConcurrentBuilds = "true".equalsIgnoreCase(System.getenv("BRK_NO_CONCURRENT_BUILDS"));
        if (noConcurrentBuilds) {
            var lock = acquireBuildLock(cm);
            if (lock == null) return runBuildAndUpdateFragmentInternal(ctx, verificationCommand, override);
            try (var ignored = lock) {
                return runBuildAndUpdateFragmentInternal(ctx, verificationCommand, override);
            }
        } else {
            return runBuildAndUpdateFragmentInternal(ctx, verificationCommand, override);
        }
    }

    @Blocking
    public static Context runExplicitCommand(Context ctx, String command, @Nullable BuildDetails override)
            throws InterruptedException {
        var cm = ctx.getContextManager();
        var io = cm.getIo();
        if (command.isBlank()) {
            io.llmOutput("\nNo explicit command specified, skipping.", ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);
            return ctx.withBuildResult(true, "");
        }
        boolean noConcurrentBuilds = "true".equalsIgnoreCase(System.getenv("BRK_NO_CONCURRENT_BUILDS"));
        if (noConcurrentBuilds) {
            var lock = acquireBuildLock(cm);
            if (lock == null) return runExplicitBuildAndUpdateFragmentInternal(ctx, command, override);
            try (var ignored = lock) {
                return runExplicitBuildAndUpdateFragmentInternal(ctx, command, override);
            }
        } else {
            return runExplicitBuildAndUpdateFragmentInternal(ctx, command, override);
        }
    }

    private record BuildLock(FileChannel channel, FileLock lock, Path lockFile) implements AutoCloseable {
        @Override
        public void close() {
            try {
                if (lock.isValid()) lock.release();
            } catch (Exception e) {
                logger.debug("Error releasing build lock", e);
            }
            try {
                if (channel.isOpen()) channel.close();
            } catch (Exception e) {
                logger.debug("Error closing build lock channel", e);
            }
        }
    }

    private static @Nullable BuildLock acquireBuildLock(IContextManager cm) {
        Path lockDir = Paths.get(System.getProperty("java.io.tmpdir"), "brokk");
        try {
            Files.createDirectories(lockDir);
        } catch (IOException e) {
            return null;
        }
        var repoNameForLock = getOriginRepositoryName(cm);
        Path lockFile = lockDir.resolve(repoNameForLock + ".lock");
        try {
            var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            var lock = channel.lock();
            return new BuildLock(channel, lock, lockFile);
        } catch (IOException ioe) {
            return null;
        }
    }

    private static String getOriginRepositoryName(IContextManager cm) {
        var url = cm.getRepo().getRemoteUrl();
        if (url == null || url.isBlank())
            return cm.getRepo().getGitTopLevel().getFileName().toString();
        if (url.endsWith(".git")) url = url.substring(0, url.length() - 4);
        int idx = Math.max(url.lastIndexOf('/'), url.lastIndexOf(':'));
        if (idx >= 0 && idx < url.length() - 1) return url.substring(idx + 1);
        throw new IllegalArgumentException("Unable to parse git repo url " + url);
    }

    private static Context runBuildAndUpdateFragmentInternal(
            Context ctx, String verificationCommand, @Nullable BuildDetails override) throws InterruptedException {
        var cm = ctx.getContextManager();
        var io = cm.getIo();
        var details = override != null ? override : cm.getProject().awaitBuildDetails();
        @Nullable String testRetriesEnv = System.getenv("BRK_TEST_RETRIES");
        if (testRetriesEnv != null && !testRetriesEnv.isBlank()) {
            return runBuildWithTestRetries(ctx, verificationCommand, details, Integer.parseInt(testRetriesEnv.trim()));
        }
        io.llmOutput("\nRunning verification command:", ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);
        io.llmOutput(
                verificationCommand + "\n\n",
                ChatMessageType.CUSTOM,
                LlmOutputMeta.newMessage().withTerminal(true));
        try {
            Environment.instance.runShellCommand(
                    verificationCommand,
                    cm.getProject().getRoot(),
                    line -> io.llmOutput(line + "\n", ChatMessageType.CUSTOM, LlmOutputMeta.terminal()),
                    resolveTimeout(cm.getProject().getRunCommandTimeoutSeconds()),
                    cm.getProject().getShellConfig(),
                    details.environmentVariables());
            return ctx.withBuildResult(true, "Build succeeded.");
        } catch (Environment.SubprocessException e) {
            return ctx.withBuildResult(
                    false,
                    BuildOutputProcessor.processForLlm(
                            Objects.toString(e.getMessage(), "") + "\n\n" + Objects.toString(e.getOutput(), ""), cm));
        }
    }

    private static Context runBuildWithTestRetries(
            Context ctx, String verificationCommand, BuildDetails details, int maxRetries) throws InterruptedException {
        var cm = ctx.getContextManager();
        var io = cm.getIo();
        String lintCommand = details.buildLintCommand();
        String testCommand = verificationCommand.equals(lintCommand) ? "" : verificationCommand;
        io.llmOutput(
                "\nRunning verification with test retries enabled:", ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);
        if (!lintCommand.isBlank())
            io.llmOutput(
                    "\nLint/compile: " + lintCommand + "\n",
                    ChatMessageType.CUSTOM,
                    LlmOutputMeta.newMessage().withTerminal(true));
        if (!testCommand.isBlank())
            io.llmOutput(
                    "Test: " + testCommand + " (up to " + maxRetries + " attempts)\n\n",
                    ChatMessageType.CUSTOM,
                    LlmOutputMeta.terminal());
        var result = BuildVerifier.verifyWithRetries(
                cm.getProject(),
                lintCommand,
                testCommand,
                maxRetries,
                details.environmentVariables(),
                line -> io.llmOutput(line + "\n", ChatMessageType.CUSTOM, LlmOutputMeta.terminal()));
        if (result.success()) return ctx.withBuildResult(true, "Build succeeded.");
        else return ctx.withBuildResult(false, BuildOutputProcessor.processForLlm(result.output(), cm));
    }

    private static Context runExplicitBuildAndUpdateFragmentInternal(
            Context ctx, String command, @Nullable BuildDetails override) throws InterruptedException {
        var cm = ctx.getContextManager();
        var io = cm.getIo();
        io.llmOutput(
                "\nRunning command: \n\n```bash\n" + command + "\n```\n",
                ChatMessageType.CUSTOM,
                LlmOutputMeta.DEFAULT);
        io.llmOutput(
                "\n```" + ShellConfig.getShellLanguageFromProject(cm.getProject()) + "\n",
                ChatMessageType.CUSTOM,
                LlmOutputMeta.DEFAULT);
        try {
            var details = override != null ? override : cm.getProject().awaitBuildDetails();
            Environment.instance.runShellCommand(
                    command,
                    cm.getProject().getRoot(),
                    line -> io.llmOutput(line + "\n", ChatMessageType.CUSTOM, LlmOutputMeta.terminal()),
                    resolveTimeout(cm.getProject().getTestCommandTimeoutSeconds()),
                    cm.getProject().getShellConfig(),
                    details.environmentVariables());
            io.llmOutput("\n```", ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);
            return ctx.withBuildResult(true, "Build succeeded.");
        } catch (Environment.SubprocessException e) {
            io.llmOutput("\n```", ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);
            return ctx.withBuildResult(
                    false,
                    BuildOutputProcessor.processForLlm(
                            Objects.toString(e.getMessage(), "") + "\n\n" + Objects.toString(e.getOutput(), ""), cm));
        }
    }

    private static Duration resolveTimeout(long timeoutSeconds) {
        if (timeoutSeconds == -1) return Environment.UNLIMITED_TIMEOUT;
        else if (timeoutSeconds <= 0) return Environment.DEFAULT_TIMEOUT;
        else return Duration.ofSeconds(timeoutSeconds);
    }
}
