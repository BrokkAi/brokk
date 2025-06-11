package io.github.jbellis.brokk.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.jbellis.brokk.AnalyzerUtil;
import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.util.HtmlToMarkdown;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


/**
 * Provides tools for manipulating the context (adding/removing files and fragments)
 * and adding analysis results (usages, skeletons, sources, call graphs) as context fragments.
 */
public class WorkspaceTools {
    private static final Logger logger = LogManager.getLogger(WorkspaceTools.class);

    // Changed field type to concrete ContextManager
    private final ContextManager contextManager;

    // Changed constructor parameter type to concrete ContextManager
    public WorkspaceTools(ContextManager contextManager) {
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager cannot be null");
    }

    @Tool("Edit project files to the Workspace. Use this when Code Agent will need to make changes to these files, or if you need to read the full source. Only call when you have identified specific filenames. DO NOT call this to create new files -- Code Agent can do that without extra steps.")
    public String addFilesToWorkspace(
            @P("List of file paths relative to the project root (e.g., 'src/main/java/com/example/MyClass.java'). Must not be empty.")
            List<String> relativePaths
    )
    {
        if (relativePaths == null || relativePaths.isEmpty()) {
            return "File paths list cannot be empty.";
        }

        List<ProjectFile> projectFiles = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String path : relativePaths) {
            if (path == null || path.isBlank()) {
                errors.add("Null or blank path provided.");
                continue;
            }
            var file = contextManager.toFile(path);
            if (!file.exists()) {
                errors.add("File at `%s` does not exist (remember, don't use this method to create new files)".formatted(path));
                continue;
            }
            projectFiles.add(file);
        }

        contextManager.editFiles(projectFiles);
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

    @Tool("Add classes to the Workspace by their fully qualified names. This maps class names to their containing files and adds those files for editing. Only call when you have identified specific class names.\")")
    public String addClassesToWorkspace(
            @P("List of fully qualified class names (e.g., ['com.example.MyClass', 'org.another.Util']). Must not be empty.")
            List<String> classNames
    )
    {
        if (classNames == null || classNames.isEmpty()) {
            return "Class names list cannot be empty.";
        }

        List<ProjectFile> filesToAdd = new ArrayList<>();
        List<String> classesNotFound = new ArrayList<>();
        var analyzer = getAnalyzer();

        for (String className : classNames) {
            if (className == null || className.isBlank()) {
                classesNotFound.add("<blank or null>"); // Indicate a bad entry in the input list
                continue;
            }
            var fileOpt = analyzer.getFileFor(className); // Returns Optional now
            if (fileOpt.isPresent()) { // Use isPresent() for Optional
                filesToAdd.add(fileOpt.get());
            } else {
                classesNotFound.add(className);
                logger.warn("Could not find file for class: {}", className);
            }
        }

        if (filesToAdd.isEmpty()) {
            return "Could not find files for any of the provided class names: " + classNames;
        }

        // Remove duplicates before adding
        var distinctFiles = filesToAdd.stream().distinct().toList();
        contextManager.editFiles(distinctFiles);

        String addedFileNames = distinctFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(", "));
        String resultMessage = "Added %s containing requested classes to the workspace".formatted(addedFileNames);

        if (!classesNotFound.isEmpty()) {
            resultMessage += ". Could not find files for the following classes: [%s]".formatted(String.join(", ", classesNotFound));
        }

        return resultMessage;
    }

    @Tool("Fetch content from a URL (e.g., documentation, issue tracker) and add it to the Workspace as a read-only text fragment. HTML content will be converted to Markdown.")
    public String addUrlContentsToWorkspace(
            @P("The full URL to fetch content from (e.g., 'https://example.com/docs/page').")
            String urlString
    )
    {
        if (urlString == null || urlString.isBlank()) {
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
        var fragment = new ContextFragment.StringFragment(contextManager, content, description, SyntaxConstants.SYNTAX_STYLE_NONE); // Pass contextManager
        contextManager.pushContext(ctx -> ctx.addVirtualFragment(fragment));

        return "Added content from URL [%s] as a read-only text fragment.".formatted(urlString);
    }

    @Tool("Add an arbitrary block of text (e.g., notes that are independent of the Plan, a configuration snippet, or something learned from another Agent) to the Workspace as a read-only fragment")
    public String addTextToWorkspace(
            @P("The text content to add to the Workspace")
            String content,
            @P("A short, descriptive label for this text fragment (e.g., 'User Requirements', 'API Key Snippet')")
            String description
    )
    {
        if (content == null || content.isBlank()) {
            return "Content cannot be empty.";
        }
        if (description == null || description.isBlank()) {
            return "Description cannot be empty.";
        }

        // Use the ContextManager's method to add the string fragment
        var fragment = new ContextFragment.StringFragment(contextManager, content, description, SyntaxConstants.SYNTAX_STYLE_NONE); // Pass contextManager
        contextManager.pushContext(ctx -> ctx.addVirtualFragment(fragment));

        return "Added text '%s'.".formatted(description);
    }

    @Tool("Remove specified fragments (files, text snippets, task history, analysis results) from the Workspace using their unique string IDs")
    public String dropWorkspaceFragments(
            @P("List of string IDs corresponding to the fragments visible in the workspace that you want to remove. Must not be empty.")
            List<String> fragmentIds
    )
    {
        if (fragmentIds == null || fragmentIds.isEmpty()) {
            return "Fragment IDs list cannot be empty.";
        }

        var currentContext = contextManager.topContext();
        var allFragments = currentContext.getAllFragmentsInDisplayOrder();
        var idsToDropSet = new HashSet<>(fragmentIds);

        var toDrop = allFragments.stream()
                .filter(frag -> idsToDropSet.contains(frag.id()))
                .toList();

        if (!toDrop.isEmpty()) {
            contextManager.drop(toDrop);
            return "Dropped %d fragment(s) with IDs: [%s]".formatted(toDrop.size(), toDrop);
        } else {
            return "No valid fragments found to drop for the given IDs: " + fragmentIds;
        }
    }

    @Tool(value = """
                  Finds usages of a specific symbol (class, method, field) and adds the full source of the calling methods to the Workspace. Only call when you have identified specific symbols.")
                  """)
    public String addSymbolUsagesToWorkspace(
            @P("Fully qualified symbol name (e.g., 'com.example.MyClass', 'com.example.MyClass.myMethod', 'com.example.MyClass.myField') to find usages for.")
            String symbol
    )
    {
        assert getAnalyzer().isCpg() : "Cannot add usages: Code Intelligence is not available.";
        if (symbol == null || symbol.isBlank()) {
            return "Cannot add usages: symbol cannot be empty";
        }

        // Create UsageFragment with only the target symbol.
        // The fragment itself will compute the usages when its text() or sources() is called.
        var fragment = new ContextFragment.UsageFragment(contextManager, symbol); // Pass contextManager
        contextManager.addVirtualFragment(fragment);

        // The message indicates addition; actual fetching confirmation happens when fragment is rendered/used.
        return "Added dynamic usage analysis for symbol '%s'.".formatted(symbol);
    }

    @Tool(value = """
                  Retrieves summaries (fields and method signatures) for specified classes and adds them to the Workspace.
                  Faster and more efficient than reading entire files or classes when you just need the API and not the full source code.
                  Only call when you have identified specific class names.")
                  """)
    public String addClassSummariesToWorkspace(
            @P("List of fully qualified class names (e.g., ['com.example.ClassA', 'org.another.ClassB']) to get summaries for. Must not be empty.")
            List<String> classNames
    )
    {
        assert getAnalyzer().isCpg() : "Cannot add summary: Code Intelligence is not available.";
        if (classNames == null || classNames.isEmpty()) {
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

        var fragment = new ContextFragment.SkeletonFragment(contextManager, distinctClassNames, ContextFragment.SummaryType.CLASS_SKELETON); // Pass contextManager
        contextManager.addVirtualFragment(fragment);

        return "Added dynamic class summaries for: [%s]".formatted(String.join(", ", distinctClassNames));
    }

    @Tool(value = """
                  Retrieves summaries (fields and method signatures) for all classes defined within specified project files and adds them to the Workspace.
                  Supports glob patterns: '*' matches files in a single directory, '**' matches files recursively.
                  Faster and more efficient than reading entire files when you just need the API definitions.
                  (But if you don't know where what you want is located, you should use Search Agent instead.)
                  """)
    public String addFileSummariesToWorkspace(
            @P("List of file paths relative to the project root. Supports glob patterns (* for single directory, ** for recursive). E.g., ['src/main/java/com/example/util/*.java', 'tests/foo/**.py']. Must not be empty.")
            List<String> filePaths
    )
    {
        assert getAnalyzer().isCpg() : "Cannot add summaries: Code Intelligence is not available.";
        if (filePaths == null || filePaths.isEmpty()) {
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

        var fragment = new ContextFragment.SkeletonFragment(contextManager, resolvedFilePaths, ContextFragment.SummaryType.FILE_SKELETONS); // Pass contextManager
        contextManager.addVirtualFragment(fragment);

        return "Added dynamic file summaries for: [%s]".formatted(String.join(", ", resolvedFilePaths.stream().sorted().toList()));
    }

    @Tool(value = """
                  Retrieves the full source code of specific methods and adds to the Workspace each as a separate read-only text fragment.
                  Faster and more efficient than including entire files or classes when you only need a few methods.
                  """)
    public String addMethodSourcesToWorkspace(
            @P("List of fully qualified method names (e.g., ['com.example.ClassA.method1', 'org.another.ClassB.processData']) to retrieve sources for. Must not be empty.")
            List<String> methodNames
    )
    {
        assert getAnalyzer().isCpg() : "Cannot add method sources: Code Intelligence is not available.";
        if (methodNames == null || methodNames.isEmpty()) {
            return "Cannot add method sources: method names list is empty";
        }

        var sourcesData = AnalyzerUtil.getMethodSourcesData(getAnalyzer(), methodNames);
        if (sourcesData.isEmpty()) {
            return "No sources found for methods: " + String.join(", ", methodNames);
        }

        // Add each method source as a separate StringFragment
        int count = 0;
        for (var entry : sourcesData.entrySet()) {
            String methodName = entry.getKey();
            String sourceCodeWithHeader = entry.getValue();
            String description = "Source for method " + methodName;
            // Create and add the fragment
            var fragment = new ContextFragment.StringFragment(contextManager, sourceCodeWithHeader, description, SyntaxConstants.SYNTAX_STYLE_JAVA); // Pass contextManager
            contextManager.addVirtualFragment(fragment);
            count++;
        }

        return "Added %d method source(s) for: [%s]".formatted(count, String.join(", ", sourcesData.keySet()));
    }

    @Tool("Returns the file paths relative to the project root for the given fully-qualified class names.")
    public String getFiles(
            @P("List of fully qualified class names (e.g., ['com.example.MyClass', 'org.another.Util']). Must not be empty.")
            List<String> classNames
    )
    {
        assert getAnalyzer().isCpg() : "Cannot get files: Code Intelligence is not available.";
        if (classNames == null || classNames.isEmpty()) {
            return "Class names list cannot be empty.";
        }

        List<String> foundFiles = new ArrayList<>();
        List<String> notFoundClasses = new ArrayList<>();
        var analyzer = getAnalyzer();

        classNames.stream().distinct().forEach(className -> {
            if (className == null || className.isBlank()) {
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

        String resultMessage = "Files found: " + String.join(", ", foundFiles.stream().sorted().toList()); // Sort for consistent output

        if (!notFoundClasses.isEmpty()) {
            resultMessage += ". Could not find files for the following classes: [%s]".formatted(String.join(", ", notFoundClasses));
        }

        return resultMessage;
    }

    // disabled until we can do this efficiently in Joern
//    @Tool(value = """
//    Retrieves the full source code of specified classes and adds each as a separate read-only text fragment.
//    More efficient than reading an entire file when you only need a single class, particularly for inner classes
//    """)
//    public String addClassSourcesFragment(
//            @P("List of fully qualified class names (e.g., ['com.example.ClassA', 'org.another.ClassB']) to retrieve the full source code for.")
//            List<String> classNames
//    ) {
//        assert getAnalyzer().isCpg() : "Cannot add class sources: Code Intelligence is not available.";
//        if (classNames == null || classNames.isEmpty()) {
//            return "Cannot add class sources: class names list is empty";
//        }
//        // Removed reasoning check
//
//        var sourcesData = AnalyzerUtil.getClassSourcesData(getAnalyzer(), classNames);
//        if (sourcesData.isEmpty()) {
//            return "No sources found for classes: " + String.join(", ", classNames);
//        }
//
//        // Add each class source as a separate StringFragment
//        int count = 0;
//        for (var entry : sourcesData.entrySet()) {
//            String className = entry.getKey();
//            String sourceCodeWithHeader = entry.getValue();
//            String description = "Source for class " + className;
//            // Create and add the fragment
//            var fragment = new ContextFragment.StringFragment(sourceCodeWithHeader, description);
//            contextManager.addVirtualFragment(fragment);
//            count++;
//        }
//
//        return "Added %d class source fragment(s) for: [%s]".formatted(count, String.join(", ", sourcesData.keySet()));
//    }

    @Tool(value = """
                  Generates a call graph showing methods that call the specified target method (callers) up to a certain depth, and adds it to the Workspace.
                  The single line of the call sites (but not full method sources) are included
                  """)
    public String addCallGraphInToWorkspace(
            @P("Fully qualified target method name (e.g., 'com.example.MyClass.targetMethod') to find callers for.")
            String methodName,
            @P("Maximum depth of the call graph to retrieve (e.g., 3 or 5). Higher depths can be large.")
            int depth // Added depth parameter
    )
    {
        assert getAnalyzer().isCpg() : "Cannot add call graph: CPG analyzer is not available.";
        if (methodName == null || methodName.isBlank()) {
            return "Cannot add call graph: method name is empty";
        }
        if (depth <= 0) {
            return "Cannot add call graph: depth must be positive";
        }

        var fragment = new ContextFragment.CallGraphFragment(contextManager, methodName, depth, false); // false for callers, pass contextManager
        contextManager.addVirtualFragment(fragment);

        return "Added call graph (callers) for '%s' (depth %d).".formatted(methodName, depth);
    }

    @Tool(value = """
                  Generates a call graph showing methods called by the specified source method (callees) up to a certain depth, and adds it to the workspace
                  The single line of the call sites (but not full method sources) are included
                  """)
    public String addCallGraphOutToWorkspace(
            @P("Fully qualified source method name (e.g., 'com.example.MyClass.sourceMethod') to find callees for.")
            String methodName,
            @P("Maximum depth of the call graph to retrieve (e.g., 3 or 5). Higher depths can be large.")
            int depth // Added depth parameter
    )
    {
        assert getAnalyzer().isCpg() : "Cannot add call graph: CPG analyzer is not available.";
        if (methodName == null || methodName.isBlank()) {
            return "Cannot add call graph: method name is empty";
        }
        if (depth <= 0) {
            return "Cannot add call graph: depth must be positive";
        }

        var fragment = new ContextFragment.CallGraphFragment(contextManager, methodName, depth, true); // true for callees, pass contextManager
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

        try (var reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private IAnalyzer getAnalyzer() {
        return contextManager.getAnalyzerUninterrupted();
    }
}
