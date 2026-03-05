package ai.brokk.util;

import ai.brokk.ContextManager;
import ai.brokk.IContextManager;
import ai.brokk.LlmOutputMeta;
import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.project.IProject;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import dev.langchain4j.data.message.ChatMessageType;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

        var projectFilesFromEditableOrReadOnly = ctx.allFragments()
                .filter(f -> f.getType().isPath())
                .flatMap(fragment -> fragment.sourceFiles().join().stream());

        var projectFilesFromSkeletons = ctx.allFragments()
                .filter(vf -> vf.getType() == ContextFragment.FragmentType.SKELETON)
                .flatMap(skeletonFragment -> skeletonFragment.sourceFiles().join().stream());

        var workspaceFiles = Stream.concat(projectFilesFromEditableOrReadOnly, projectFilesFromSkeletons)
                .collect(Collectors.toSet());

        var analyzer = cm.getAnalyzer();
        var workspaceTestFiles = (testFilesOverride != null && !testFilesOverride.isEmpty())
                ? testFilesOverride
                : workspaceFiles.stream()
                        .filter(f -> ContextManager.isTestFile(f, analyzer))
                        .toList();

        if (workspaceTestFiles.isEmpty()) {
            // If there are no discovered test targets for a SOME-scoped run, we should still prefer running the
            // project's "test all" command (when available) over dropping down to lint-only. This keeps verification
            // semantics consistent and matches BuildVerifier's lint-then-test behavior.
            String testAll = System.getenv("BRK_TESTALL_CMD") != null
                    ? System.getenv("BRK_TESTALL_CMD")
                    : details.testAllCommand();

            if (testAll != null && !testAll.isBlank()) {
                return interpolateCommandWithPythonVersion(
                        testAll, cm.getProject().getRoot());
            }

            return interpolateCommandWithPythonVersion(
                    details.buildLintCommand(), cm.getProject().getRoot());
        }

        return getBuildLintSomeCommand(cm, details, workspaceTestFiles);
    }

    public static CompletableFuture<@Nullable String> determineVerificationCommandAsync(IContextManager cm) {
        return cm.submitBackgroundTask(
                "Determine build verification command", () -> determineVerificationCommand(cm.liveContext()));
    }

    private static @Nullable String getPythonVersionForProject(Path projectRoot) {
        try {
            return new EnvironmentPython(projectRoot).getPythonVersion();
        } catch (Exception e) {
            logger.debug("Unable to determine Python version for project", e);
            return null;
        }
    }

    private static String interpolateCommandWithPythonVersion(String command, Path projectRoot) {
        if (command.isEmpty()) return command;
        String pythonVersion = getPythonVersionForProject(projectRoot);
        return interpolateMustacheTemplate(command, List.of(), "unused", pythonVersion);
    }

    /**
     * Generates a command for building or testing a specific subset of files/classes.
     */
    public static String getBuildLintSomeCommand(
            IContextManager cm, BuildDetails details, Collection<ProjectFile> workspaceTestFiles)
            throws InterruptedException {
        String template = details.testSomeCommand();
        if (template.isBlank()) {
            return interpolateCommandWithPythonVersion(
                    details.buildLintCommand(), cm.getProject().getRoot());
        }

        IProject project = cm.getProject();
        var analyzer = cm.getAnalyzer();

        Map<String, Object> mustacheContext = new HashMap<>();
        mustacheContext.put(
                "packages", MustacheTemplates.toStringElementList(analyzer.getTestModules(workspaceTestFiles)));

        // Extract both top-level and all declarations to ensure we find test functions/classes
        List<ai.brokk.analyzer.CodeUnit> declarations = workspaceTestFiles.stream()
                .flatMap(f -> {
                    var top = analyzer.getTopLevelDeclarations(f);
                    return top.isEmpty() ? analyzer.getDeclarations(f).stream() : top.stream();
                })
                .toList();

        mustacheContext.put(
                "fqclasses",
                MustacheTemplates.toStringElementList(declarations.stream()
                        .map(ai.brokk.analyzer.CodeUnit::fqName)
                        .distinct()
                        .sorted()
                        .toList()));
        mustacheContext.put(
                "classes",
                MustacheTemplates.toStringElementList(declarations.stream()
                        .map(ai.brokk.analyzer.CodeUnit::shortName)
                        .distinct()
                        .sorted()
                        .toList()));
        mustacheContext.put(
                "files",
                MustacheTemplates.toStringElementList(workspaceTestFiles.stream()
                        .map(ProjectFile::toString)
                        .distinct()
                        .sorted()
                        .toList()));
        mustacheContext.put("pyver", Objects.toString(getPythonVersionForProject(project.getRoot()), ""));

        MustacheFactory mf = new DefaultMustacheFactory();
        Mustache mustache = mf.compile(new StringReader(template), "test_some_interpolation");
        StringWriter writer = new StringWriter();
        mustache.execute(writer, mustacheContext);
        return writer.toString();
    }

    private static final Set<String> ALLOWED_TOP_LEVEL_KEYS =
            Set.of("files", "classes", "fqclasses", "packages", "pyver");

    private static final Set<String> ALLOWED_ITEM_KEYS = Set.of(".", "value", "first", "last", "index");

    private static final Pattern DELIMITER_CHANGE_PATTERN = Pattern.compile("\\{\\{=.*?=\\}\\}");

    private static final Pattern MUSTACHE_TAG_PATTERN =
            Pattern.compile("\\{\\{\\{?\\s*([#/^>!]?)\\s*([^}\\s]+)\\s*\\}?\\}\\}\\s*");

    /**
     * Interpolates a Mustache template with the given list of items and optional Python version.
     * Supports {@code {{#files}}}, {@code {{#classes}}}, {@code {{#fqclasses}}}, {@code {{#packages}}},
     * and {@code {{pyver}}} variables.
     */
    public static String interpolateMustacheTemplate(String template, List<String> items, String listKey) {
        return interpolateMustacheTemplate(template, items, listKey, null);
    }

    /**
     * Interpolates a Mustache template with the given list of items and optional Python version.
     */
    public static String interpolateMustacheTemplate(
            String template, List<? extends Object> items, String listKey, @Nullable String pythonVersion) {
        if (template.isEmpty()) {
            return "";
        }

        // Validate template before compiling
        validateMustacheTemplate(template, listKey);

        MustacheFactory mf = new DefaultMustacheFactory();
        Mustache mustache = mf.compile(new StringReader(template), "dynamic_template");

        Map<String, Object> context = new HashMap<>();
        context.put(
                listKey,
                MustacheTemplates.toStringElementList(
                        items.stream().map(Object::toString).toList()));
        context.put("pyver", pythonVersion == null ? "" : pythonVersion);

        StringWriter writer = new StringWriter();
        mustache.execute(writer, context);

        return writer.toString();
    }

    private static void validateMustacheTemplate(String template, String listKey) {
        if (template.isEmpty()) {
            return;
        }
        var tags = new java.util.LinkedHashSet<String>();
        var delimiterMatcher = DELIMITER_CHANGE_PATTERN.matcher(template);
        while (delimiterMatcher.find()) {
            String fullMatch = delimiterMatcher.group();
            String delimiterSpec =
                    fullMatch.substring(3, fullMatch.length() - 3).trim();
            tags.add("=" + (delimiterSpec.isEmpty() ? "..." : delimiterSpec));
        }

        var matcher = MUSTACHE_TAG_PATTERN.matcher(template);
        while (matcher.find()) {
            String prefix = matcher.group(1);
            String name = matcher.group(2);
            if (">".equals(prefix) || "!".equals(prefix)) {
                tags.add(prefix + name);
            } else {
                tags.add(name);
            }
        }

        var allAllowed = new java.util.HashSet<>(ALLOWED_TOP_LEVEL_KEYS);
        allAllowed.addAll(ALLOWED_ITEM_KEYS);
        allAllowed.add(listKey);

        var unsupported = new java.util.LinkedHashSet<String>();
        for (String tag : tags) {
            if (!allAllowed.contains(tag)) {
                unsupported.add(tag);
            }
        }

        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException("Unsupported Mustache tags: %s. Allowed: %s"
                    .formatted(unsupported, new java.util.TreeSet<>(allAllowed)));
        }
    }

    /**
     * Executes an explicit command and updates the context with the result.
     * If the command is blank, it clears any previous build errors.
     */
    @Blocking
    public static Context runExplicitCommand(Context ctx, String command, @Nullable BuildDetails override)
            throws InterruptedException {
        var cm = ctx.getContextManager();
        var io = cm.getIo();

        if (command.isBlank()) {
            io.llmOutput("\nNo explicit command specified, skipping.", ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);
            return ctx.withBuildResult(true, "Build succeeded.");
        }

        var details = override != null ? override : cm.getProject().awaitBuildDetails();

        io.llmOutput("\nRunning explicit command:", ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);
        io.llmOutput(
                command + "\n\n",
                ChatMessageType.CUSTOM,
                LlmOutputMeta.newMessage().withTerminal(true));

        Duration timeout = Duration.ofSeconds(cm.getProject().getTestCommandTimeoutSeconds());
        if (timeout.isNegative()) timeout = Environment.UNLIMITED_TIMEOUT;

        try {
            ai.brokk.util.Environment.instance.runShellCommand(
                    command,
                    cm.getProject().getRoot(),
                    line -> io.llmOutput(line + "\n", ChatMessageType.CUSTOM, LlmOutputMeta.terminal()),
                    timeout,
                    cm.getProject().getShellConfig(),
                    details.environmentVariables());
            return ctx.withBuildResult(true, "Build succeeded.");
        } catch (ai.brokk.util.Environment.SubprocessException e) {
            String output = Objects.toString(e.getOutput(), "");
            return ctx.withBuildResult(
                    false, "Command failed: " + Objects.toString(e.getMessage(), "") + "\n" + output);
        }
    }

    /**
     * Convenience wrapper that runs verification and returns the error string (blank on success).
     * Pushes the result to the ContextManager's live context if possible.
     */
    @Blocking
    public static String runVerification(IContextManager cm) throws InterruptedException {
        return runVerification(cm, null);
    }

    /**
     * Convenience wrapper that runs verification with an override and returns the error string.
     * Pushes the result to the ContextManager's live context if possible.
     */
    @Blocking
    public static String runVerification(IContextManager cm, @Nullable BuildDetails override)
            throws InterruptedException {
        try {
            var interrupted = new AtomicReference<InterruptedException>(null);
            var updated = cm.pushContext(ctx -> {
                try {
                    return runVerification(ctx, override);
                } catch (InterruptedException e) {
                    interrupted.set(e);
                    return ctx;
                }
            });
            if (interrupted.get() != null) throw interrupted.get();

            String error = updated.getBuildError();
            return error.isBlank() ? "Build succeeded." : error;
        } catch (UnsupportedOperationException e) {
            // For TestContextManager or other doubles that don't support pushContext
            String error = runVerification(cm.liveContext(), override).getBuildError();
            return error.isBlank() ? "Build succeeded." : error;
        }
    }

    @Blocking
    public static Context runVerification(Context ctx) throws InterruptedException {
        return runVerificationInternal(ctx, null, null);
    }

    @Blocking
    public static Context runVerification(Context ctx, @Nullable BuildDetails override) throws InterruptedException {
        return runVerificationInternal(ctx, override, null);
    }

    @Blocking
    public static Context runVerification(
            Context ctx, @Nullable BuildDetails override, @Nullable Collection<ProjectFile> testFilesOverride)
            throws InterruptedException {
        return runVerificationInternal(ctx, override, testFilesOverride);
    }

    private static Context runVerificationInternal(
            Context ctx, @Nullable BuildDetails override, @Nullable Collection<ProjectFile> testFilesOverride)
            throws InterruptedException {
        var cm = ctx.getContextManager();
        var io = cm.getIo();
        var details = override != null ? override : cm.getProject().awaitBuildDetails();
        var verificationCommand = determineVerificationCommand(ctx, override, testFilesOverride);
        if (verificationCommand == null || verificationCommand.isBlank()) {
            io.llmOutput(
                    "\nNo verification command specified, skipping build/check.",
                    ChatMessageType.CUSTOM,
                    LlmOutputMeta.DEFAULT);
            return ctx.withBuildResult(true, "Build succeeded.");
        }

        String lintCommand = details.buildLintCommand();
        String testCommand = verificationCommand.equals(lintCommand) ? "" : verificationCommand;

        // Output messages to IO similar to original behavior
        io.llmOutput("\nRunning verification command:", ChatMessageType.CUSTOM, LlmOutputMeta.DEFAULT);
        if (!lintCommand.isBlank()) {
            io.llmOutput("\nLint: " + lintCommand, ChatMessageType.CUSTOM, LlmOutputMeta.terminal());
        }
        if (!testCommand.isBlank()) {
            io.llmOutput("\nTest: " + testCommand, ChatMessageType.CUSTOM, LlmOutputMeta.terminal());
        }
        io.llmOutput("\n\n", ChatMessageType.CUSTOM, LlmOutputMeta.newMessage().withTerminal(true));

        // Determine retries
        int retries = 1;
        String retriesEnv = System.getenv("BRK_TEST_RETRIES");
        if (retriesEnv != null) {
            try {
                retries = Integer.parseInt(retriesEnv.trim());
            } catch (NumberFormatException e) {
                logger.warn("Invalid BRK_TEST_RETRIES value: '{}'. Using default (1).", retriesEnv);
            }
        }

        var result = BuildVerifier.verifyWithRetries(
                cm.getProject(),
                lintCommand,
                testCommand,
                retries,
                details.environmentVariables(),
                line -> io.llmOutput(line + "\n", ChatMessageType.CUSTOM, LlmOutputMeta.terminal()));

        if (result.success()) {
            return ctx.withBuildResult(true, "Build succeeded.");
        } else {
            return ctx.withBuildResult(false, result.output());
        }
    }
}
