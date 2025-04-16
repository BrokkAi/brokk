package io.github.jbellis.brokk.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.jbellis.brokk.AnalyzerUtil;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.util.HtmlToMarkdown;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    @Tool("Edit project files in the Workspace. Use this when you intend to make changes to these files.")
    public String addEditableFilesToWorkspace(
            @P("List of file paths relative to the project root (e.g., 'src/main/java/com/example/MyClass.java')")
            List<String> relativePaths
    ) {
        if (relativePaths == null || relativePaths.isEmpty()) {
            throw new IllegalArgumentException("File paths list cannot be empty.");
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
                errors.add("File at `%s` does not exist.".formatted(path));
                continue;
            }
            projectFiles.add(file);
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Errors encountered processing paths: " + String.join("; ", errors));
        }

        contextManager.editFiles(projectFiles);
        String fileNames = projectFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(", "));
        return "Editing %d file(s) in the workspace: [%s]".formatted(projectFiles.size(), fileNames);
    }

    @Tool("Add read-only files to the Workspace. Use this for files you need to reference the full source of, but not modify.")
    public String addReadOnlyFilesToWorkspace(
            @P("List of file paths relative to the project root (e.g., 'src/main/java/com/example/MyClass.java')")
            List<String> paths
    ) {
        if (paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException("File paths list cannot be empty.");
        }

        List<BrokkFile> filesToAdd = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (String pathStr : paths) {
            if (pathStr == null || pathStr.isBlank()) {
                errors.add("Null or blank path provided.");
                continue;
            }

            try {
                ProjectFile pf = contextManager.toFile(pathStr);
                if (!Files.exists(pf.absPath())) {
                     errors.add("Project file does not exist: " + pathStr);
                } else if (!Files.isRegularFile(pf.absPath())) {
                    errors.add("Project path is not a regular file: " + pathStr);
                } else {
                    filesToAdd.add(pf);
                }
            } catch (InvalidPathException e) {
                errors.add("Invalid path format: " + pathStr + " (" + e.getMessage() + ")");
            } catch (Exception e) {
                errors.add("Error processing path '" + pathStr + "': " + e.getMessage());
                logger.error("Error processing path for read-only: {}", pathStr, e);
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Errors encountered processing paths: " + String.join("; ", errors));
        }

        contextManager.addReadOnlyFiles(filesToAdd);
        String fileNames = filesToAdd.stream().map(BrokkFile::toString).collect(Collectors.joining(", "));
        return "Added %d file(s) as read-only in the workspace: [%s]".formatted(filesToAdd.size(), fileNames);
    }

    @Tool("Fetch content from a URL (e.g., documentation, issue tracker) and add it to the Workspace as a read-only text fragment. HTML content will be converted to Markdown.")
    public String addUrlContentsToWorkspace(
            @P("The full URL to fetch content from (e.g., 'https://example.com/docs/page').")
            String urlString
    ) {
        if (urlString == null || urlString.isBlank()) {
            throw new IllegalArgumentException("URL cannot be empty.");
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
            throw new IllegalArgumentException("Invalid URL format: " + urlString, e);
        } catch (IOException e) {
            logger.error("Failed to fetch or process URL content: {}", urlString, e);
            throw new RuntimeException("Failed to fetch URL content for " + urlString + ": " + e.getMessage(), e);
        } catch (Exception e) {
             logger.error("Unexpected error processing URL: {}", urlString, e);
             throw new RuntimeException("Unexpected error processing URL " + urlString + ": " + e.getMessage(), e);
        }

        if (content.isBlank()) {
            throw new IllegalStateException("Fetched content from URL is empty: " + urlString);
        }

        // Use the ContextManager's method to add the string fragment
        String description = "Content from " + urlString;
        // ContextManager handles pushing the context update
        var fragment = new ContextFragment.StringFragment(content, description, SyntaxConstants.SYNTAX_STYLE_NONE);
        contextManager.pushContext(ctx -> ctx.addVirtualFragment(fragment));

        return "Added content from URL [%s] as a read-only text fragment.".formatted(urlString);
    }

    @Tool("Add an arbitrary block of text (e.g., notes that are independent of the Plan, a configuration snippet, or something learned from another Agent) to the Workspace as a read-only fragment")
    public String addTextToWorkspace(
            @P("The text content to add to the Workspace")
            String content,
            @P("A short, descriptive label for this text fragment (e.g., 'User Requirements', 'API Key Snippet')")
            String description
    ) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Content cannot be empty.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Description cannot be empty.");
        }

        // Use the ContextManager's method to add the string fragment
        var fragment = new ContextFragment.StringFragment(content, description, SyntaxConstants.SYNTAX_STYLE_NONE);
        contextManager.pushContext(ctx -> ctx.addVirtualFragment(fragment));

        return "Added text '%s'.".formatted(description);
    }

    @Tool("Remove specified fragments (files, text snippets, task history, analysis results) from the Workspace using their unique integer IDs")
    public String dropWorkspaceFragments(
            @P("List of integer IDs corresponding to the fragments visible in the workspace that you want to remove")
            List<Integer> fragmentIds
    ) {
        if (fragmentIds == null || fragmentIds.isEmpty()) {
            throw new IllegalArgumentException("Fragment IDs list cannot be empty.");
        }

        var currentContext = contextManager.topContext();
        var allFragments = currentContext.getAllFragmentsInDisplayOrder();
        var pathFragsToRemove = new ArrayList<ContextFragment.PathFragment>();
        var virtualToRemove = new ArrayList<ContextFragment.VirtualFragment>();
        var idsToDropSet = new HashSet<>(fragmentIds);
        List<Integer> foundIds = new ArrayList<>();
        boolean autoContextDropped = false;

        for (var frag : allFragments) {
            if (idsToDropSet.contains(frag.id())) {
                foundIds.add(frag.id());
                if (frag instanceof ContextFragment.AutoContext) {
                    // Special handling for AutoContext: disable it via ContextManager
                    contextManager.setAutoContextFiles(0);
                    autoContextDropped = true;
                } else if (frag instanceof ContextFragment.PathFragment pf) {
                    pathFragsToRemove.add(pf);
                } else if (frag instanceof ContextFragment.VirtualFragment vf) {
                    virtualToRemove.add(vf);
                } else {
                    logger.warn("Fragment with ID {} has unexpected type {} and cannot be dropped via this tool.", frag.id(), frag.getClass().getName());
                }
            }
        }

        List<Integer> notFoundIds = fragmentIds.stream()
                .filter(id -> !foundIds.contains(id))
                .toList();

        if (!notFoundIds.isEmpty()) {
             // Throw error if *any* requested ID wasn't found? Or just log? Let's throw.
            throw new IllegalArgumentException("Fragment IDs not found in current workspace: " + notFoundIds);
        }

        // Perform the drop operation if there's anything other than AutoContext to drop
        if (!pathFragsToRemove.isEmpty() || !virtualToRemove.isEmpty()) {
            contextManager.drop(pathFragsToRemove, virtualToRemove);
        }

        int droppedCount = pathFragsToRemove.size() + virtualToRemove.size() + (autoContextDropped ? 1 : 0);
        if (droppedCount == 0) {
            // This can happen if only invalid IDs were provided, or only AutoContext was requested but failed to drop
             throw new IllegalStateException("No valid fragments found to drop for the given IDs: " + fragmentIds);
        }

        return "Dropped %d fragment(s) with IDs: [%s]".formatted(droppedCount, foundIds.stream().map(String::valueOf).collect(Collectors.joining(", ")));
    }

    @Tool(value = """
    Finds usages of a specific symbol (class, method, field) and adds the full source of the calling methods to the Workspace.
    """)
    public String addSymbolUsagesToWorkspace(
            @P("Fully qualified symbol name (e.g., 'com.example.MyClass', 'com.example.MyClass.myMethod', 'com.example.MyClass.myField') to find usages for.")
            String symbol
    ) {
        assert !getAnalyzer().isEmpty() : "Cannot add usages: Code analyzer is not available.";
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Cannot add usages: symbol cannot be empty");
        }

        List<CodeUnit> uses = getAnalyzer().getUses(symbol);
        var result = AnalyzerUtil.processUsages(getAnalyzer(), uses);
        if (result.code().isEmpty()) {
            return "No relevant usages found for symbol: " + symbol;
        }

        var fragment = new ContextFragment.UsageFragment("Uses", symbol, result.sources(), result.code());
        contextManager.addVirtualFragment(fragment);

        return "Added usages for symbol '%s'.".formatted(symbol);
    }

    @Tool(value = """
    Retrieves summaries (fields and method signatures) for specified classes and adds them to the Workspace.
    Faster and more efficient than reading entire files or classes when you just need the API and not the full source code.
    """)
    public String addClassSummariesToWorkspace(
            @P("List of fully qualified class names (e.g., ['com.example.ClassA', 'org.another.ClassB']) to get summaries for.")
            List<String> classNames
    ) {
        assert !getAnalyzer().isEmpty() : "Cannot add summary: Code analyzer is not available.";
        if (classNames == null || classNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot add summary: class names list is empty");
        }

        var skeletonsData = AnalyzerUtil.getClassSkeletonsData(getAnalyzer(), classNames);
        if (skeletonsData.isEmpty()) {
            throw new IllegalStateException("No summaries found for classes: " + String.join(", ", classNames));
        }

        // We need to filter CodeUnits potentially added by the Util method if they are inner classes whose parent is also present
        // Re-apply the coalescing logic here, or rely on the caller providing coalesced names? Let's apply here for safety.
        // However, getClassSkeletonsData returns Map<CodeUnit, String>, so we need to adapt.
        Set<CodeUnit> unitsWithSkeletons = skeletonsData.keySet();
        Set<CodeUnit> coalescedUnits = unitsWithSkeletons.stream()
                .filter(cu -> {
                    String name = cu.fqName();
                    if (!name.contains("$")) return true; // Keep non-inner classes
                    String parent = name.substring(0, name.indexOf('$'));
                    // Keep if the parent skeleton is *not* also in the map
                    return unitsWithSkeletons.stream().noneMatch(other -> other.fqName().equals(parent));
                })
                .collect(Collectors.toSet());

        // Filter the original map based on coalesced units
        Map<CodeUnit, String> coalescedSkeletons = skeletonsData.entrySet().stream()
                .filter(entry -> coalescedUnits.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (coalescedSkeletons.isEmpty()) {
             // This could happen if only inner classes were requested and their parents were also found
             throw new IllegalStateException("No primary summaries found after coalescing for classes: " + String.join(", ", classNames));
        }

        var fragment = new ContextFragment.SkeletonFragment(coalescedSkeletons);
        contextManager.addVirtualFragment(fragment);

        String addedClasses = coalescedSkeletons.keySet().stream().map(CodeUnit::fqName).sorted().collect(Collectors.joining(", "));
        return "Added summaries for %d class(es): [%s]".formatted(coalescedSkeletons.size(), addedClasses);
    }


    @Tool(value = """
    Retrieves the full source code of specific methods and adds to the Workspace each as a separate read-only text fragment.
    Faster and more efficient than including entire files or classes when you only need a few methods.
    """)
    public String addMethodSources(
            @P("List of fully qualified method names (e.g., ['com.example.ClassA.method1', 'org.another.ClassB.processData']) to retrieve sources for")
            List<String> methodNames
    ) {
        assert !getAnalyzer().isEmpty() : "Cannot add method sources: Code analyzer is not available.";
        if (methodNames == null || methodNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot add method sources: method names list is empty");
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
            var fragment = new ContextFragment.StringFragment(sourceCodeWithHeader, description, SyntaxConstants.SYNTAX_STYLE_JAVA);
            contextManager.addVirtualFragment(fragment);
            count++;
        }

        return "Added %d method source(s) for: [%s]".formatted(count, String.join(", ", sourcesData.keySet()));
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
//        assert !getAnalyzer().isEmpty() : "Cannot add class sources: Code analyzer is not available.";
//        if (classNames == null || classNames.isEmpty()) {
//            throw new IllegalArgumentException("Cannot add class sources: class names list is empty");
//        }
//        // Removed reasoning check
//
//        var sourcesData = AnalyzerUtil.getClassSourcesData(getAnalyzer(), classNames);
//        if (sourcesData.isEmpty()) {
//            throw new IllegalStateException("No sources found for classes: " + String.join(", ", classNames));
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
    ) {
        assert !getAnalyzer().isEmpty() : "Cannot add call graph: Code analyzer is not available.";
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("Cannot add call graph: method name is empty");
        }
        if (depth <= 0) {
             throw new IllegalArgumentException("Cannot add call graph: depth must be positive");
        }

        var graphData = getAnalyzer().getCallgraphTo(methodName, depth);
        if (graphData.isEmpty()) {
            throw new IllegalStateException("No call graph available (callers) for method: " + methodName);
        }

        String formattedGraph = AnalyzerUtil.formatCallGraph(graphData, methodName, false); // false = callers (arrows point TO method)
        if (formattedGraph.isEmpty()) {
            // Should not happen if graphData is not empty, but check defensively
             throw new IllegalStateException("Failed to format non-empty call graph (callers) for method: " + methodName);
        }

        // Extract the class from the method name for sources
        Set<CodeUnit> sources = new HashSet<>();
        String className = ContextFragment.toClassname(methodName);
        var sourceFile = getAnalyzer().getFileFor(className);
        sourceFile.foreach(pf -> sources.add(CodeUnit.cls(pf, className))); // Use foreach for Option

        // Use UsageFragment to represent the call graph
        String type = "Callers (depth " + depth + ")";
        var fragment = new ContextFragment.UsageFragment(type, methodName, sources, formattedGraph);
        contextManager.addVirtualFragment(fragment);

        int totalCallSites = graphData.values().stream().mapToInt(List::size).sum();
        return "Added call graph fragment (%d sites) for callers of '%s' (depth %d).".formatted(totalCallSites, methodName, depth);
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
    ) {
        assert !getAnalyzer().isEmpty() : "Cannot add call graph: Code analyzer is not available.";
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("Cannot add call graph: method name is empty");
        }
         if (depth <= 0) {
             throw new IllegalArgumentException("Cannot add call graph: depth must be positive");
        }

         var graphData = getAnalyzer().getCallgraphFrom(methodName, depth);
        if (graphData.isEmpty()) {
            throw new IllegalStateException("No call graph available (callees) for method: " + methodName);
        }

        String formattedGraph = AnalyzerUtil.formatCallGraph(graphData, methodName, true); // true = callees (arrows point FROM method)
        if (formattedGraph.isEmpty()) {
             // Should not happen if graphData is not empty, but check defensively
             throw new IllegalStateException("Failed to format non-empty call graph (callees) for method: " + methodName);
        }

        // Extract the class from the method name for sources
        Set<CodeUnit> sources = new HashSet<>();
        String className = ContextFragment.toClassname(methodName);
        var sourceFile = getAnalyzer().getFileFor(className);
        sourceFile.foreach(pf -> sources.add(CodeUnit.cls(pf, className))); // Use foreach for Option

        // Use UsageFragment to represent the call graph
        String type = "Callees (depth " + depth + ")";
        var fragment = new ContextFragment.UsageFragment(type, methodName, sources, formattedGraph);
        contextManager.addVirtualFragment(fragment);

        int totalCallSites = graphData.values().stream().mapToInt(List::size).sum();
        return "Added call graph fragment (%d sites) for callees of '%s' (depth %d).".formatted(totalCallSites, methodName, depth);
    }

    // --- Helper Methods ---

    /**
     * Fetches content from a given URL. Public static for reuse.
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
                new InputStreamReader(connection.getInputStream()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private IAnalyzer getAnalyzer() {
        return contextManager.getProject().getAnalyzer();
    }
}
