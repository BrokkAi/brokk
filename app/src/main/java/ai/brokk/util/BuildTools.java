package ai.brokk.util;

import ai.brokk.ContextManager;
import ai.brokk.IContextManager;
import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.project.IProject;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

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
            return interpolateCommandWithPythonVersion(cmd, cm.getProject());
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
            return interpolateCommandWithPythonVersion(details.buildLintCommand(), cm.getProject());
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

        IProject project = cm.getProject();
        String pythonVersion =
                pythonVersionOverride != null ? pythonVersionOverride : getPythonVersionForProject(project);

        IAnalyzer analyzer = cm.getAnalyzer();
        Map<String, Object> context = new HashMap<>();
        context.put("pyver", pythonVersion == null ? "" : pythonVersion);

        // Always calculate all potential lists to support mixed templates
        // 1. Packages
        List<String> packages =
                analyzer.getTestModules(workspaceTestFiles).stream().distinct().toList();
        context.put("packages", MustacheTemplates.toStringElementList(packages));

        // 2. Files
        context.put(
                "files",
                MustacheTemplates.toStringElementList(workspaceTestFiles.stream()
                        .map(f -> f.toString().replace('\\', '/'))
                        .distinct()
                        .toList()));

        // 3. Classes
        List<String> fqClasses = List.of();
        List<String> classes = List.of();
        if (!analyzer.isEmpty()) {
            var codeUnits = analyzer.testFilesToCodeUnits(workspaceTestFiles);
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

        // Perform multi-variable interpolation
        MustacheFactory mf = new DefaultMustacheFactory();
        Mustache mustache = mf.compile(new StringReader(testSomeTemplate), "dynamic_template");

        StringWriter writer = new StringWriter();
        mustache.execute(writer, context);
        String result = writer.toString();

        // If the result is blank, or it is identical to a template that contains mustache tags,
        // it means no sections matched or no targets were found; fall back to build/lint command.
        if (result.isBlank() || (testSomeTemplate.contains("{{") && result.equals(testSomeTemplate))) {
            return details.buildLintCommand();
        }

        return result;
    }

    private static boolean shouldResolvePythonVersion(IProject project) {
        return project.getAnalyzerLanguages().contains(Languages.PYTHON);
    }

    private static @Nullable String getPythonVersionForProject(IProject project) {
        if (!shouldResolvePythonVersion(project)) {
            return null;
        }
        try {
            return new EnvironmentPython(project.getRoot()).getPythonVersion();
        } catch (Exception e) {
            logger.debug("Unable to determine Python version for project", e);
            return null;
        }
    }

    private static String interpolateCommandWithPythonVersion(String command, IProject project) {
        if (command.isEmpty()) return command;
        if (System.getenv("BRK_TESTALL_CMD") != null) command = System.getenv("BRK_TESTALL_CMD");
        String pythonVersion = getPythonVersionForProject(project);
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
