package io.github.jbellis.brokk.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.agents.ContextAgent;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.util.HtmlToMarkdown;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

/**
 * Provides tools for manipulating the context (adding/removing files and fragments) and adding analysis results
 * (usages, skeletons, sources, call graphs) as context fragments.
 */
public class WorkspaceTools {
    private static final Logger logger = LogManager.getLogger(WorkspaceTools.class);

    // Changed field type to concrete ContextManager
    private final ContextManager contextManager;

    // Changed constructor parameter type to concrete ContextManager
    public WorkspaceTools(ContextManager contextManager) {
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager cannot be null");
    }

    public static void addToWorkspace(
            ContextManager contextManager, ContextAgent.RecommendationResult recommendationResult) {
        logger.debug("Recommended context fits within final budget.");
        List<ContextFragment> selected = recommendationResult.fragments();
        // Group selected fragments by type
        var groupedByType = selected.stream().collect(Collectors.groupingBy(ContextFragment::getType));

        // Process ProjectPathFragments
        var pathFragments = groupedByType.getOrDefault(ContextFragment.FragmentType.PROJECT_PATH, List.of()).stream()
                .map(ContextFragment.ProjectPathFragment.class::cast)
                .toList();
        if (!pathFragments.isEmpty()) {
            logger.debug(
                    "Adding selected ProjectPathFragments: {}",
                    pathFragments.stream()
                            .map(ContextFragment.ProjectPathFragment::shortDescription)
                            .collect(Collectors.joining(", ")));
            contextManager.addPathFragments(pathFragments);
        }

        // Process SkeletonFragments
        var skeletonFragments = groupedByType.getOrDefault(ContextFragment.FragmentType.SKELETON, List.of()).stream()
                .map(ContextFragment.SkeletonFragment.class::cast)
                .toList();

        if (!skeletonFragments.isEmpty()) {
            // For CLASS_SKELETON, collect all target FQNs.
            // For FILE_SKELETONS, collect all target file paths.
            // Create one fragment per type.
            List<String> classTargetFqns = skeletonFragments.stream()
                    .filter(sf -> sf.getSummaryType() == ContextFragment.SummaryType.CODEUNIT_SKELETON)
                    .flatMap(sf -> sf.getTargetIdentifiers().stream())
                    .distinct()
                    .toList();

            List<String> fileTargetPaths = skeletonFragments.stream()
                    .filter(sf -> sf.getSummaryType() == ContextFragment.SummaryType.FILE_SKELETONS)
                    .flatMap(sf -> sf.getTargetIdentifiers().stream())
                    .distinct()
                    .toList();

            if (!classTargetFqns.isEmpty()) {
                logger.debug("Adding combined SkeletonFragment for classes: {}", classTargetFqns);
                contextManager.addVirtualFragment(new ContextFragment.SkeletonFragment(
                        contextManager, classTargetFqns, ContextFragment.SummaryType.CODEUNIT_SKELETON));
            }
            if (!fileTargetPaths.isEmpty()) {
                logger.debug("Adding combined SkeletonFragment for files: {}", fileTargetPaths);
                contextManager.addVirtualFragment(new ContextFragment.SkeletonFragment(
                        contextManager, fileTargetPaths, ContextFragment.SummaryType.FILE_SKELETONS));
            }
        }
    }

    @Tool(
            "Edit project files to the Workspace. Use this when Code Agent will need to make changes to these files, or if you need to read the full source. Only call when you have identified specific filenames. DO NOT call this to create new files -- Code Agent can do that without extra steps.")
    public String addFilesToWorkspace(
            @P(
                            "List of file paths relative to the project root (e.g., 'src/main/java/com/example/MyClass.java'). Must not be empty.")
                    List<String> relativePaths) {
        if (relativePaths.isEmpty()) {
            return "File paths list cannot be empty.";
        }

        List<ProjectFile> projectFiles = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String path : relativePaths) {
            if (path.isBlank()) {
                errors.add("Null or blank path provided.");
                continue;
            }
            try {
                var file = contextManager.toFile(path);
                if (!file.exists()) {
                    errors.add("File at `%s` does not exist (remember, don't use this method to create new files)"
                            .formatted(path));
                    continue;
                }
                projectFiles.add(file);
            } catch (IllegalArgumentException e) {
                errors.add("Invalid path: " + path);
            }
        }

        contextManager.addFiles(projectFiles);
        String fileNames = projectFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(", "));
        String result = "";
        if (!fileNames.isEmpty()) {
            result += "Added %s to the workspace. ".formatted(fileNames);
        }
        if (!errors.isEmpty()) {
            result += "Errors were [%s]".formatted(String.join(", ", errors));
        }
        return result;
    }

    @Tool(
            "Add classes to the Workspace by their fully qualified names. This adds read-only code fragments for those classes. Only call when you have identified specific class names.")
    public String addClassesToWorkspace(
            @P(
                            "List of fully qualified class names (e.g., ['com.example.MyClass', 'org.another.Util']). Must not be empty.")
                    List<String> classNames) {
        assert getAnalyzer() instanceof SourceCodeProvider
                : "Cannot add class sources: Code Intelligence is not available.";
        if (classNames.isEmpty()) {
            return "Class names list cannot be empty.";
        }

        int addedCount = 0;
        List<String> classesNotFound = new ArrayList<>();
        var analyzer = getAnalyzer();

        for (String className : classNames.stream().distinct().toList()) {
            if (className.isBlank()) {
                classesNotFound.add("<blank>");
                continue;
            }
            var defOpt = analyzer.getDefinition(className);
            if (defOpt.isPresent()) {
                var fragment = new ContextFragment.CodeFragment(contextManager, defOpt.get());
                contextManager.addVirtualFragment(fragment);
                addedCount++;
            } else {
                classesNotFound.add(className);
                logger.warn("Could not find definition for class: {}", className);
            }
        }

        if (addedCount == 0) {
            return "Could not find definitions for any of the provided class names: " + String.join(", ", classNames);
        }

        var resultMessage = "Added %d code fragment(s) for requested classes".formatted(addedCount);
        if (!classesNotFound.isEmpty()) {
            resultMessage += ". Could not find definitions for: [%s]".formatted(String.join(", ", classesNotFound));
        }
        return resultMessage + ".";
    }

    @Tool(
            "Fetch content from a URL (e.g., documentation, issue tracker) and add it to the Workspace as a read-only text fragment. HTML content will be converted to Markdown.")
    public String addUrlContentsToWorkspace(
            @P("The full URL to fetch content from (e.g., 'https://example.com/docs/page').") String urlString) {
        if (urlString.isBlank()) {
            return "URL cannot be empty.";
        }

        String content;
        URI uri;
        try {
            uri = new URI(urlString);
            logger.debug("Fetching content from URL: {}", urlString);
            content = fetchUrlContent(uri);
            logger.debug("Fetched {} characters from {}", content.length(), urlString);
            // Directly call the utility method for conversion
            content = HtmlToMarkdown.maybeConvertToMarkdown(content);
            logger.debug("Content length after potential markdown conversion: {}", content.length());
        } catch (URISyntaxException e) {
            return "Invalid URL format: " + urlString;
        } catch (IOException e) {
            logger.error("Failed to fetch or process URL content: {}", urlString, e);
            throw new RuntimeException("Failed to fetch URL content for " + urlString + ": " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error processing URL: {}", urlString, e);
            throw new RuntimeException("Unexpected error processing URL " + urlString + ": " + e.getMessage(), e);
        }

        if (content.isBlank()) {
            return "Fetched content from URL is empty: " + urlString;
        }

        // Use the ContextManager's method to add the string fragment
        String description = "Content from " + urlString;
        // ContextManager handles pushing the context update
        var fragment = new ContextFragment.StringFragment(
                contextManager, content, description, SyntaxConstants.SYNTAX_STYLE_NONE); // Pass contextManager
        contextManager.pushContext(ctx -> ctx.addVirtualFragment(fragment));

        return "Added content from URL [%s] as a read-only text fragment.".formatted(urlString);
    }

    @Tool(
            "Add an arbitrary block of text (e.g., notes that are independent of the Plan, a configuration snippet, or something learned from another Agent) to the Workspace as a read-only fragment")
    public String addTextToWorkspace(
            @P("The text content to add to the Workspace") String content,
            @P("A short, descriptive label for this text fragment (e.g., 'User Requirements', 'API Key Snippet')")
                    String description) {
        if (content.isBlank()) {
            return "Content cannot be empty.";
        }
        if (description.isBlank()) {
            return "Description cannot be empty.";
        }

        // Use the ContextManager's method to add the string fragment
        var fragment = new ContextFragment.StringFragment(
                contextManager, content, description, SyntaxConstants.SYNTAX_STYLE_NONE); // Pass contextManager
        contextManager.pushContext(ctx -> ctx.addVirtualFragment(fragment));

        return "Added text '%s'.".formatted(description);
    }

    @Tool(
            value =
                    "Remove specified fragments (files, text snippets, task history, analysis results) from the Workspace using their unique string IDs")
    public String dropWorkspaceFragments(
            @P(
                            "List of string IDs corresponding to the fragments visible in the workspace that you want to remove. Must not be empty.")
                    List<String> fragmentIds) {
        if (fragmentIds.isEmpty()) {
            return "Fragment IDs list cannot be empty.";
        }

        var currentContext = contextManager.liveContext();
        var allFragments = currentContext.getAllFragmentsInDisplayOrder();
        var idsToDropSet = new HashSet<>(fragmentIds);

        var toDrop = allFragments.stream()
                .filter(frag -> idsToDropSet.contains(frag.id()))
                .toList();

        if (!toDrop.isEmpty()) {
            contextManager.drop(toDrop);
            var droppedReprs = toDrop.stream().map(ContextFragment::repr).collect(Collectors.joining(", "));
            return "Dropped %d fragment(s): [%s]".formatted(toDrop.size(), droppedReprs);
        } else {
            return "No valid fragments found to drop for the given IDs: " + fragmentIds;
        }
    }

    @Tool(
            """
                  Finds usages of a specific symbol (class, method, field) and adds the full source of the calling methods to the Workspace. Only call when you have identified specific symbols.")
                  """)
    public String addSymbolUsagesToWorkspace(
            @P(
                            "Fully qualified symbol name (e.g., 'com.example.MyClass', 'com.example.MyClass.myMethod', 'com.example.MyClass.myField') to find usages for.")
                    String symbol) {
        assert getAnalyzer() instanceof UsagesProvider : "Cannot add usages: Code Intelligence is not available.";
        if (symbol.isBlank()) {
            return "Cannot add usages: symbol cannot be empty";
        }

        // Create UsageFragment with only the target symbol.
        // The fragment itself will compute the usages when its text() or sources() is called.
        var fragment = new ContextFragment.UsageFragment(contextManager, symbol); // Pass contextManager
        contextManager.addVirtualFragment(fragment);

        // The message indicates addition; actual fetching confirmation happens when fragment is rendered/used.
        return "Added dynamic usage analysis for symbol '%s'.".formatted(symbol);
    }

    @Tool(
            """
                  Retrieves summaries (fields and method signatures) for specified classes and adds them to the Workspace.
                  Faster and more efficient than reading entire files or classes when you just need the API and not the full source code.
                  Only call when you have identified specific class names.")
                  """)
    public String addClassSummariesToWorkspace(
            @P(
                            "List of fully qualified class names (e.g., ['com.example.ClassA', 'org.another.ClassB']) to get summaries for. Must not be empty.")
                    List<String> classNames) {
        assert getAnalyzer() instanceof SkeletonProvider : "Cannot add summary: Code Intelligence is not available.";
        if (classNames.isEmpty()) {
            return "Cannot add summary: class names list is empty";
        }

        // Coalesce inner classes to their top-level parents if both are requested or found.
        // This is simpler if we just pass all requested FQNs to SkeletonFragment
        // and let its text() / sources() handle fetching and eventual coalescing if needed during display.
        // For now, just pass the direct list.
        List<String> distinctClassNames = classNames.stream().distinct().toList();
        if (distinctClassNames.isEmpty()) {
            return "Cannot add summary: class names list resolved to empty";
        }

        var fragment = new ContextFragment.SkeletonFragment(
                contextManager,
                distinctClassNames,
                ContextFragment.SummaryType.CODEUNIT_SKELETON); // Pass contextManager
        contextManager.addVirtualFragment(fragment);

        return "Added dynamic class summaries for: [%s]".formatted(String.join(", ", distinctClassNames));
    }

    @Tool(
            """
                  Retrieves summaries (fields and method signatures) for all classes defined within specified project files and adds them to the Workspace.
                  Supports glob patterns: '*' matches files in a single directory, '**' matches files recursively.
                  Faster and more efficient than reading entire files when you just need the API definitions.
                  (But if you don't know where what you want is located, you should use Search Agent instead.)
                  """)
    public String addFileSummariesToWorkspace(
            @P(
                            "List of file paths relative to the project root. Supports glob patterns (* for single directory, ** for recursive). E.g., ['src/main/java/com/example/util/*.java', 'tests/foo/**.py']. Must not be empty.")
                    List<String> filePaths) {
        assert getAnalyzer() instanceof SkeletonProvider : "Cannot add summaries: Code Intelligence is not available.";
        if (filePaths.isEmpty()) {
            return "Cannot add summaries: file paths list is empty";
        }

        var project = contextManager.getProject();
        List<String> resolvedFilePaths = filePaths.stream() // Changed variable name and type
                .flatMap(pattern -> Completions.expandPath(project, pattern).stream())
                .filter(ProjectFile.class::isInstance)
                .map(ProjectFile.class::cast)
                .map(ProjectFile::toString) // Store paths as strings
                .distinct()
                .toList();

        if (resolvedFilePaths.isEmpty()) {
            return "No project files found matching the provided patterns: " + String.join(", ", filePaths);
        }

        var fragment = new ContextFragment.SkeletonFragment(
                contextManager, resolvedFilePaths, ContextFragment.SummaryType.FILE_SKELETONS); // Pass contextManager
        contextManager.addVirtualFragment(fragment);

        return "Added dynamic file summaries for: [%s]"
                .formatted(String.join(", ", resolvedFilePaths.stream().sorted().toList()));
    }

    @Tool(
            """
                  Retrieves the full source code of specific methods and adds to the Workspace each as a separate read-only text fragment.
                  Faster and more efficient than including entire files or classes when you only need a few methods.
                  """)
    public String addMethodsToWorkspace(
            @P(
                            "List of fully qualified method names (e.g., ['com.example.ClassA.method1', 'org.another.ClassB.processData']) to retrieve sources for. Must not be empty.")
                    List<String> methodNames) {
        assert getAnalyzer() instanceof SourceCodeProvider
                : "Cannot add method sources: Code Intelligence is not available.";
        if (methodNames.isEmpty()) {
            return "Cannot add method sources: method names list is empty";
        }

        int count = 0;
        List<String> notFound = new ArrayList<>();

        var analyzer = getAnalyzer();
        for (String methodName : methodNames.stream().distinct().toList()) {
            if (methodName.isBlank()) {
                continue;
            }
            var cuOpt = analyzer.getDefinition(methodName);
            if (cuOpt.isPresent() && cuOpt.get().isFunction()) {
                var fragment = new ContextFragment.CodeFragment(contextManager, cuOpt.get());
                contextManager.addVirtualFragment(fragment);
                count++;
            } else {
                notFound.add(methodName);
                logger.warn("Could not find method definition for: {}", methodName);
            }
        }

        if (count == 0) {
            return "No sources found for methods: " + String.join(", ", methodNames);
        }

        var msg = "Added %d method source(s)".formatted(count);
        if (!notFound.isEmpty()) {
            msg += ". Could not find methods: [%s]".formatted(String.join(", ", notFound));
        }
        return msg + ".";
    }

    @Tool("Returns the file paths relative to the project root for the given fully-qualified class names.")
    public String getFiles(
            @P(
                            "List of fully qualified class names (e.g., ['com.example.MyClass', 'org.another.Util']). Must not be empty.")
                    List<String> classNames) {
        if (classNames.isEmpty()) {
            return "Class names list cannot be empty.";
        }

        List<String> foundFiles = new ArrayList<>();
        List<String> notFoundClasses = new ArrayList<>();
        var analyzer = getAnalyzer();

        classNames.stream().distinct().forEach(className -> {
            if (className.isBlank()) {
                notFoundClasses.add("<blank or null>");
                return;
            }
            var fileOpt = analyzer.getFileFor(className); // Returns Optional now
            if (fileOpt.isPresent()) { // Use isPresent()
                foundFiles.add(fileOpt.get().toString());
            } else {
                notFoundClasses.add(className);
                logger.warn("Could not find file for class: {}", className);
            }
        });

        if (foundFiles.isEmpty()) {
            return "Could not find files for any of the provided class names: " + String.join(", ", classNames);
        }

        String resultMessage = "Files found: "
                + String.join(", ", foundFiles.stream().sorted().toList()); // Sort for consistent output

        if (!notFoundClasses.isEmpty()) {
            resultMessage += ". Could not find files for the following classes: [%s]"
                    .formatted(String.join(", ", notFoundClasses));
        }

        return resultMessage;
    }

    @Tool(
            """
                  Generates a call graph showing methods that call the specified target method (callers) up to a certain depth, and adds it to the Workspace.
                  The single line of the call sites (but not full method sources) are included
                  """)
    public String addCallGraphInToWorkspace(
            @P("Fully qualified target method name (e.g., 'com.example.MyClass.targetMethod') to find callers for.")
                    String methodName,
            @P("Maximum depth of the call graph to retrieve (e.g., 3 or 5). Higher depths can be large.")
                    int depth // Added depth parameter
            ) {
        assert (getAnalyzer() instanceof CallGraphProvider) : "Cannot add call graph: CPG analyzer is not available.";
        if (methodName.isBlank()) {
            return "Cannot add call graph: method name is empty";
        }
        if (depth <= 0) {
            return "Cannot add call graph: depth must be positive";
        }

        var fragment = new ContextFragment.CallGraphFragment(
                contextManager, methodName, depth, false); // false for callers, pass contextManager
        contextManager.addVirtualFragment(fragment);

        return "Added call graph (callers) for '%s' (depth %d).".formatted(methodName, depth);
    }

    @Tool(
            """
                  Generates a call graph showing methods called by the specified source method (callees) up to a certain depth, and adds it to the workspace
                  The single line of the call sites (but not full method sources) are included
                  """)
    public String addCallGraphOutToWorkspace(
            @P("Fully qualified source method name (e.g., 'com.example.MyClass.sourceMethod') to find callees for.")
                    String methodName,
            @P("Maximum depth of the call graph to retrieve (e.g., 3 or 5). Higher depths can be large.")
                    int depth // Added depth parameter
            ) {
        assert (getAnalyzer() instanceof CallGraphProvider) : "Cannot add call graph: CPG analyzer is not available.";
        if (methodName.isBlank()) {
            return "Cannot add call graph: method name is empty";
        }
        if (depth <= 0) {
            return "Cannot add call graph: depth must be positive";
        }

        var fragment = new ContextFragment.CallGraphFragment(
                contextManager, methodName, depth, true); // true for callees, pass contextManager
        contextManager.addVirtualFragment(fragment);

        return "Added call graph (callees) for '%s' (depth %d).".formatted(methodName, depth);
    }

    // --- Helper Methods ---

    /**
     * Fetches content from a given URL. Public static for reuse.
     *
     * @param url The URL to fetch from.
     * @return The content as a String.
     * @throws IOException If fetching fails.
     */
    public static String fetchUrlContent(URI url) throws IOException { // Changed to public static
        var connection = url.toURL().openConnection();
        // Set reasonable timeouts
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        // Set a user agent
        connection.setRequestProperty("User-Agent", "Brokk-Agent/1.0 (ContextTools)");

        try (var reader =
                new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private IAnalyzer getAnalyzer() {
        return contextManager.getAnalyzerUninterrupted();
    }
}
