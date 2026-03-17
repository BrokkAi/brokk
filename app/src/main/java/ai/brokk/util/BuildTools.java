package ai.brokk.util;

import ai.brokk.ContextManager;
import ai.brokk.IContextManager;
import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.project.FileFilteringService;
import ai.brokk.project.IProject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

public class BuildTools {
    private static final Logger logger = LogManager.getLogger(BuildTools.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
        BuildDetails details = override != null ? override : getEffectiveBuildDetails(cm.getProject());

        if (details.equals(BuildDetails.EMPTY)) {
            logger.warn("No build details available, cannot determine verification command.");
            return null;
        }

        var project = cm.getProject();
        IProject.CodeAgentTestScope testScope = project.getCodeAgentTestScope();

        if (testScope == IProject.CodeAgentTestScope.ALL && details.testAllEnabled()) {
            String cmd = System.getenv("BRK_TESTALL_CMD") != null
                    ? System.getenv("BRK_TESTALL_CMD")
                    : details.testAllCommand();
            return interpolateCommandWithPythonVersion(cmd, project);
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
            return interpolateCommandWithPythonVersion(details.buildLintCommand(), project);
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

        // Group files by their most specific module
        Map<BuildAgent.ModuleBuildEntry, List<ProjectFile>> moduleToFiles = new HashMap<>();
        List<ProjectFile> fallbackFiles = new ArrayList<>();

        for (ProjectFile f : workspaceTestFiles) {
            String unixPath = FileFilteringService.toUnixPath(f.getRelPath());
            var bestModule = details.modules().stream()
                    .filter(m -> {
                        String rel = m.relativePath();
                        if (rel.equals(".") || rel.isEmpty()) {
                            return true;
                        }
                        // m.relativePath() is normalized to end with '/' for non-root modules.
                        // We check if the file is inside that directory or is the directory itself.
                        return unixPath.startsWith(rel) || unixPath.equals(rel.substring(0, rel.length() - 1));
                    })
                    .max(Comparator.comparingInt(m -> m.relativePath().length()))
                    .orElse(null);

            if (bestModule != null && !bestModule.testSomeCommand().isBlank()) {
                moduleToFiles
                        .computeIfAbsent(bestModule, k -> new ArrayList<>())
                        .add(f);
            } else {
                fallbackFiles.add(f);
            }
        }

        List<String> commands = new ArrayList<>();
        List<Map.Entry<BuildAgent.ModuleBuildEntry, List<ProjectFile>>> sortedEntries =
                moduleToFiles.entrySet().stream()
                        .sorted(Comparator.comparing(e -> e.getKey().relativePath()))
                        .toList();

        for (var entry : sortedEntries) {
            var module = entry.getKey();
            var files = entry.getValue();
            String template = module.testSomeCommand();
            if (!template.isBlank()) {
                String interpolated = interpolateModuleCommand(cm, template, files, pythonVersionOverride);
                if (!interpolated.isBlank()) {
                    commands.add(interpolated);
                }
            }
        }

        if (!fallbackFiles.isEmpty()
                && details.testSomeEnabled()
                && !details.testSomeCommand().isBlank()) {
            String interpolated =
                    interpolateModuleCommand(cm, details.testSomeCommand(), fallbackFiles, pythonVersionOverride);
            if (!interpolated.isBlank()) {
                commands.add(interpolated);
            }
        }

        if (!commands.isEmpty()) {
            return String.join(" && ", commands);
        }

        return details.buildLintEnabled() ? details.buildLintCommand() : "";
    }

    private static String interpolateModuleCommand(
            IContextManager cm,
            String template,
            Collection<ProjectFile> testFiles,
            @Nullable String pythonVersionOverride)
            throws InterruptedException {
        var project = cm.getProject();
        var pythonVersion = pythonVersionOverride != null
                ? Optional.of(pythonVersionOverride)
                : getPythonVersionForProject(project);

        IAnalyzer analyzer = cm.getAnalyzer();
        Map<String, Object> context = new HashMap<>();
        context.put("pyver", pythonVersion.orElse(""));

        // 1. Packages
        List<String> packages =
                analyzer.getTestModules(testFiles).stream().distinct().toList();
        context.put("packages", MustacheTemplates.toStringElementList(packages));

        // 2. Files
        context.put(
                "files",
                MustacheTemplates.toStringElementList(testFiles.stream()
                        .map(f -> f.toString().replace('\\', '/'))
                        .distinct()
                        .toList()));

        // 3. Classes
        List<String> fqClasses = List.of();
        List<String> classes = List.of();
        if (!analyzer.isEmpty()) {
            var codeUnits = analyzer.testFilesToCodeUnits(testFiles);
            fqClasses =
                    codeUnits.stream().map(CodeUnit::fqName).distinct().sorted().toList();
            classes = codeUnits.stream()
                    .map(CodeUnit::identifier)
                    .distinct()
                    .sorted()
                    .toList();
        }
        context.put("fqclasses", MustacheTemplates.toStringElementList(fqClasses));
        context.put("classes", MustacheTemplates.toStringElementList(classes));

        MustacheFactory mf = new DefaultMustacheFactory();
        Mustache mustache = mf.compile(new StringReader(template), "dynamic_template");

        StringWriter writer = new StringWriter();
        mustache.execute(writer, context);
        String result = writer.toString();

        if (result.isBlank() || (template.contains("{{") && result.equals(template))) {
            return "";
        }

        return result;
    }

    /**
     * Retrieves the BuildDetails for a project, allowing for environment variable overrides.
     */
    @Blocking
    public static BuildDetails getEffectiveBuildDetails(IProject project) throws InterruptedException {
        BuildDetails details = project.awaitBuildDetails();

        boolean buildLintEnabled = System.getenv("BRK_BUILDLINT_ENABLED") != null
                ? Boolean.parseBoolean(System.getenv("BRK_BUILDLINT_ENABLED"))
                : details.buildLintEnabled();

        boolean testAllEnabled = System.getenv("BRK_TESTALL_ENABLED") != null
                ? Boolean.parseBoolean(System.getenv("BRK_TESTALL_ENABLED"))
                : details.testAllEnabled();

        boolean testSomeEnabled = System.getenv("BRK_TESTSOME_ENABLED") != null
                ? Boolean.parseBoolean(System.getenv("BRK_TESTSOME_ENABLED"))
                : details.testAllEnabled();

        List<BuildAgent.ModuleBuildEntry> modules = new ArrayList<>(details.modules());
        String modulesJson = System.getenv("BRK_MODULES_JSON");
        if (modulesJson != null && !modulesJson.isBlank()) {
            modules = new ArrayList<>(parseModulesJson(modulesJson));
        }

        String testSomeCommand = System.getenv("BRK_TESTSOME_CMD") != null
                ? System.getenv("BRK_TESTSOME_CMD")
                : details.testSomeCommand();

        return new BuildDetails(
                details.buildLintCommand(),
                buildLintEnabled,
                details.testAllCommand(),
                testAllEnabled,
                testSomeCommand,
                testSomeEnabled,
                details.exclusionPatterns(),
                details.environmentVariables(),
                details.maxBuildAttempts(),
                details.afterTaskListCommand(),
                modules);
    }

    @VisibleForTesting
    public static List<BuildAgent.ModuleBuildEntry> parseModulesJson(String json) {
        try {
            var tf = OBJECT_MAPPER.getTypeFactory();
            var type = tf.constructCollectionType(List.class, BuildAgent.ModuleBuildEntry.class);
            return OBJECT_MAPPER.readValue(json, type);
        } catch (Exception e) {
            logger.error("Failed to deserialize BRK_MODULES_JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private static Optional<String> getPythonVersionForProject(IProject project) {
        return getPythonVersionForProject(project, null);
    }

    @VisibleForTesting
    static Optional<String> getPythonVersionForProject(
            IProject project, @Nullable Predicate<String> pythonExecutableChecker) {
        if (!project.getAnalyzerLanguages().contains(Languages.PYTHON)) {
            return Optional.empty();
        }

        var projectRoot = project.getRoot();

        @Nullable
        BiPredicate<Path, Boolean> ignoreChecker =
                (project.hasGit() || !project.getExclusionPatterns().isEmpty()) ? project::shouldSkipPath : null;

        try {
            var env = pythonExecutableChecker != null
                    ? new EnvironmentPython(projectRoot, ignoreChecker, pythonExecutableChecker)
                    : new EnvironmentPython(projectRoot, ignoreChecker);
            return Optional.of(env.getPythonVersion());
        } catch (Exception e) {
            logger.debug("Unable to determine Python version for project", e);
            return Optional.empty();
        }
    }

    private static String interpolateCommandWithPythonVersion(String command, IProject project) {
        if (command.isEmpty()) return command;
        if (System.getenv("BRK_TESTALL_CMD") != null) command = System.getenv("BRK_TESTALL_CMD");
        var pythonVersion = getPythonVersionForProject(project).orElse(null);
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
}
