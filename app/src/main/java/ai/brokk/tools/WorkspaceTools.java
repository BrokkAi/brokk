package ai.brokk.tools;

import ai.brokk.AbstractProject;
import ai.brokk.AnalyzerUtil;
import ai.brokk.ContextManager;
import ai.brokk.ExceptionReporter;
import ai.brokk.analyzer.*;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SkeletonProvider;
import ai.brokk.analyzer.SourceCodeProvider;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.util.Json;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Provides tools for manipulating the context (adding/removing files and fragments) and adding analysis results
 * (usages, skeletons, sources, call graphs) as context fragments.
 *
 * This class is now context-local: it holds a working Context instance and mutates it immutably.
 * Use WorkspaceTools(Context) for per-turn, local usage inside agents. For compatibility, a constructor taking a
 * ContextManager is provided which seeds the local Context from the manager; call publishTo(cm) to commit changes.
 */
public class WorkspaceTools {
    private static final Logger logger = LogManager.getLogger(WorkspaceTools.class);

    // Per-instance working context (immutable Context instances replaced on modification)
    private Context context;

    /**
     * Construct a WorkspaceTools instance operating on the provided Context.
     * This is the preferred constructor for per-turn/local usage inside agents.
     */
    public WorkspaceTools(Context initialContext) {
        this.context = initialContext;
    }

    /**
     * Compatibility constructor used by callers that previously passed a ContextManager.
     * This seeds the working Context from cm.liveContext() and retains a reference to cm so callers can call
     * publishTo(cm) to commit the changes as a single atomic push.
     */
    public WorkspaceTools(ContextManager cm) {
        this.context = cm.liveContext();
    }

    /** Returns the current working Context for this WorkspaceTools instance. */
    public Context getContext() {
        return context;
    }

    /** Updates the working Context for this WorkspaceTools instance. */
    public void setContext(Context newContext) {
        this.context = newContext;
    }

    // ---------------------------
    // Tools (mutate the local Context)
    // ---------------------------

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
                var file = context.getContextManager().toFile(path);
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

        // Determine already-present files and files-to-add based on current workspace state
        var workspaceFiles = currentWorkspaceFiles();
        var distinctRequested = new HashSet<>(projectFiles);
        var toAddFiles = distinctRequested.stream()
                .filter(f -> !workspaceFiles.contains(f))
                .toList();
        var alreadyPresent = distinctRequested.stream()
                .filter(workspaceFiles::contains)
                .map(ProjectFile::toString)
                .sorted()
                .toList();

        var fragments = context.getContextManager().toPathFragments(toAddFiles);
        context = context.addPathFragments(fragments);

        String addedNames =
                toAddFiles.stream().map(ProjectFile::toString).sorted().collect(Collectors.joining(", "));
        String result = "";
        if (addedNames.isEmpty()) {
            result += "No new files added.\n";
        } else {
            result += "Added to the workspace: %s\n".formatted(addedNames);
        }
        if (!alreadyPresent.isEmpty()) {
            result += "Already present (no-op): %s.\n".formatted(String.join(", ", alreadyPresent));
        }
        if (!errors.isEmpty()) {
            result += "Errors were: [%s]\n".formatted(String.join(", ", errors));
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

        int initialFragmentCount = (int) context.allFragments().count();
        context = Context.withAddedClasses(context, classNames, getAnalyzer());
        int addedCount = (int) context.allFragments().count() - initialFragmentCount;

        if (addedCount == 0) {
            return "Could not find definitions for any of the provided class names: " + String.join(", ", classNames);
        }

        return "Added %d code fragment(s) for requested classes.".formatted(addedCount);
    }

    @Tool(
            "Fetch content from a URL (e.g., documentation, issue tracker) and add it to the Workspace as a read-only text fragment. HTML content will be converted to Markdown.")
    public String addUrlContentsToWorkspace(
            @P("The full URL to fetch content from (e.g., 'https://example.com/docs/page').") String urlString) {
        if (urlString.isBlank()) {
            return "URL cannot be empty.";
        }

        try {
            logger.debug("Fetching content from URL: {}", urlString);
            int initialFragmentCount = (int) context.allFragments().count();
            context = Context.withAddedUrlContent(context, urlString);
            int addedCount = (int) context.allFragments().count() - initialFragmentCount;

            if (addedCount == 0) {
                return "Fetched content from URL is empty: " + urlString;
            }

            logger.debug("Successfully added URL content to context");
            return "Added content from URL [%s] as a read-only text fragment.".formatted(urlString);
        } catch (URISyntaxException e) {
            return "Invalid URL format: " + urlString;
        } catch (IOException e) {
            logger.error("Failed to fetch or process URL content: {}", urlString, e);
            throw new RuntimeException("Failed to fetch URL content for " + urlString + ": " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error processing URL: {}", urlString, e);
            ExceptionReporter.tryReportException(e);
            throw new RuntimeException("Unexpected error processing URL " + urlString + ": " + e.getMessage(), e);
        }
    }

    @Tool(
            value =
                    "Remove specified fragments (files, text snippets, task history, analysis results) from the Workspace and record explanations in DISCARDED_CONTEXT as a JSON map. Do not drop file fragments that you will need to edit as part of your current task, unless the edits are localized to a single function.")
    public String dropWorkspaceFragments(
            @P(
                            "Map of { fragmentId -> explanation } for why each fragment is being discarded. Must not be empty. 'Discarded Context' fragment is not itself drop-able.")
                    Map<String, String> idToExplanation) {
        if (idToExplanation.isEmpty()) {
            return "Fragment map cannot be empty.";
        }

        var allFragments = context.getAllFragmentsInDisplayOrder();

        // Get existing DISCARDED_CONTEXT map (we find this first so we can protect it
        // from being removed by a caller attempting to drop it).
        var existingDiscardedMap = context.getDiscardedFragmentsNote();
        var existingDiscarded = context.virtualFragments()
                .filter(vf -> vf.getType() == ContextFragment.FragmentType.STRING)
                .filter(vf -> vf instanceof ContextFragment.StringFragment)
                .map(vf -> (ContextFragment.StringFragment) vf)
                .filter(sf -> ContextFragment.DISCARDED_CONTEXT.description().equals(sf.description()))
                .findFirst();

        var idsToDropSet = new HashSet<>(idToExplanation.keySet());

        // Compute the list of fragments to drop, but explicitly exclude the existing DISCARDED_CONTEXT fragment
        // so callers cannot remove it via this API.
        List<ContextFragment> toDrop;
        if (existingDiscarded.isPresent()) {
            var protectedId = existingDiscarded.get().id();
            toDrop = allFragments.stream()
                    .filter(f -> idsToDropSet.contains(f.id()))
                    .filter(f -> !protectedId.equals(f.id()))
                    .toList();
        } else {
            toDrop = allFragments.stream()
                    .filter(f -> idsToDropSet.contains(f.id()))
                    .toList();
        }

        var droppedIds = toDrop.stream().map(ContextFragment::id).collect(Collectors.toSet());

        // Record if the caller attempted to drop the protected DISCARDED_CONTEXT (so we can mention it in the result)
        var attemptedProtected = existingDiscarded.isPresent()
                && idsToDropSet.contains(existingDiscarded.get().id());

        var mapper = Json.getMapper();
        Map<String, String> mergedDiscarded = new LinkedHashMap<>(existingDiscardedMap);

        // Merge explanations for successfully dropped fragments (new overwrites old)
        for (var f : toDrop) {
            var explanation = idToExplanation.getOrDefault(f.id(), "");
            mergedDiscarded.put(f.description(), explanation);
        }

        // Serialize updated JSON
        String discardedJson;
        try {
            discardedJson = mapper.writeValueAsString(mergedDiscarded);
        } catch (Exception e) {
            logger.error("Failed to serialize DISCARDED_CONTEXT JSON", e);
            return "Error: Failed to serialize DISCARDED_CONTEXT JSON: " + e.getMessage();
        }

        // Apply removal and update DISCARDED_CONTEXT in the local context
        var next = context.removeFragmentsByIds(droppedIds);
        if (existingDiscarded.isPresent()) {
            next = next.removeFragmentsByIds(List.of(existingDiscarded.get().id()));
        }
        var fragment = new ContextFragment.StringFragment(
                context.getContextManager(),
                discardedJson,
                ContextFragment.DISCARDED_CONTEXT.description(),
                ContextFragment.DISCARDED_CONTEXT.syntaxStyle());

        next = next.addVirtualFragment(fragment);
        context = next;

        var unknownIds =
                idsToDropSet.stream().filter(id -> !droppedIds.contains(id)).collect(Collectors.toList());
        logger.debug(
                "dropWorkspaceFragments: dropped={}, unknown={}, updatedDiscardedEntries={}, attemptedProtected={}",
                droppedIds.size(),
                unknownIds.size(),
                mergedDiscarded.size(),
                attemptedProtected);

        var droppedReprs = toDrop.stream().map(ContextFragment::repr).collect(Collectors.joining(", "));
        var baseMsg = "Dropped %d fragment(s): [%s]. Updated DISCARDED_CONTEXT with %d entr%s."
                .formatted(
                        droppedIds.size(),
                        droppedReprs,
                        mergedDiscarded.size(),
                        mergedDiscarded.size() == 1 ? "y" : "ies");

        // If the caller attempted to drop the protected DISCARDED_CONTEXT, mention that it was protected and not
        // removed.
        if (attemptedProtected) {
            baseMsg += " Note: the DISCARDED_CONTEXT fragment is protected and was not dropped.";
        }

        if (!unknownIds.isEmpty()) {
            return baseMsg + " Unknown fragment IDs: " + String.join(", ", unknownIds);
        }
        return baseMsg;
    }

    @Tool(
            """
                  Finds usages of a specific symbol (class, method, field) and adds the full source of the calling methods to the Workspace. Only call when you have identified specific symbols.")
                  """)
    public String addSymbolUsagesToWorkspace(
            @P(
                            "Fully qualified symbol name (e.g., 'com.example.MyClass', 'com.example.MyClass.myMethod', 'com.example.MyClass.myField') to find usages for.")
                    String symbol) {
        assert !getAnalyzer().isEmpty() : "Cannot add usages: Code Intelligence is not available.";
        if (symbol.isBlank()) {
            return "Cannot add usages: symbol cannot be empty";
        }

        var fragment = new ContextFragment.UsageFragment(context.getContextManager(), symbol); // Pass contextManager
        context = context.addVirtualFragments(List.of(fragment));

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

        List<String> distinctClassNames = classNames.stream().distinct().toList();
        if (distinctClassNames.isEmpty()) {
            return "Cannot add summary: class names list resolved to empty";
        }

        int initialFragmentCount = (int) context.allFragments().count();
        context = Context.withAddedClassSummaries(context, distinctClassNames);
        int addedCount = (int) context.allFragments().count() - initialFragmentCount;

        return "Added %d dynamic class summar%s for: [%s]"
                .formatted(addedCount, addedCount == 1 ? "y" : "ies", String.join(", ", distinctClassNames));
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
            return "Cannot add summaries: file paths list is empty.";
        }

        var project = (AbstractProject) context.getContextManager().getProject();
        int initialFragmentCount = (int) context.allFragments().count();
        context = Context.withAddedFileSummaries(context, filePaths, project);
        int addedCount = (int) context.allFragments().count() - initialFragmentCount;

        if (addedCount == 0) {
            return "No project files found matching the provided patterns: " + String.join(", ", filePaths);
        }

        return "Added %d dynamic file summar%s.".formatted(addedCount, addedCount == 1 ? "y" : "ies");
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

        int initialFragmentCount = (int) context.allFragments().count();
        context = Context.withAddedMethodSources(context, methodNames, getAnalyzer());
        int addedCount = (int) context.allFragments().count() - initialFragmentCount;

        if (addedCount == 0) {
            return "No sources found for methods: " + String.join(", ", methodNames);
        }

        return "Added %d method source(s).".formatted(addedCount);
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
            var fileOpt = AnalyzerUtil.getFileFor(analyzer, className);
            if (fileOpt.isPresent()) {
                foundFiles.add(fileOpt.get().toString());
            } else {
                notFoundClasses.add(className);
                logger.warn("Could not find file for class: {}", className);
            }
        });

        if (foundFiles.isEmpty()) {
            return "Could not find files for any of the provided class names: " + String.join(", ", classNames);
        }

        String resultMessage =
                "Files found: " + String.join(", ", foundFiles.stream().sorted().toList());

        if (!notFoundClasses.isEmpty()) {
            resultMessage += ". Could not find files for the following classes: [%s]"
                    .formatted(String.join(", ", notFoundClasses));
        }

        return resultMessage;
    }

    @Tool(
            "Append a Markdown-formatted note to Task Notes in the Workspace. Use this ONLY to excerpt findings for files that do not need to be kept in the Workspace. DO NOT use this to give instructions to the Code Agent: he is better at his job than you are.")
    public String appendNote(
            @P("Markdown content to append to Task Notes. NOT for speculating about implementation!") String markdown) {
        if (markdown.isBlank()) {
            return "Ignoring empty Note";
        }

        final var description = ContextFragment.SEARCH_NOTES.description();
        final var syntax = ContextFragment.SEARCH_NOTES.syntaxStyle();

        var existing = context.virtualFragments()
                .filter(vf -> vf.getType() == ContextFragment.FragmentType.STRING)
                .filter(vf -> description.equals(vf.description()))
                .findFirst();

        if (existing.isPresent()) {
            var prev = existing.get();
            String prevText = prev.text();
            String combined = prevText.isBlank() ? markdown : prevText + "\n\n" + markdown;

            var next = context.removeFragmentsByIds(List.of(prev.id()));
            var newFrag =
                    new ContextFragment.StringFragment(context.getContextManager(), combined, description, syntax);
            logger.debug(
                    "appendNote: replaced existing Task Notes fragment {} with updated content ({} chars).",
                    prev.id(),
                    combined.length());
            context = next.addVirtualFragment(newFrag);
            return "Appended note to Task Notes.";
        } else {
            var newFrag =
                    new ContextFragment.StringFragment(context.getContextManager(), markdown, description, syntax);
            logger.debug("appendNote: created new Task Notes fragment ({} chars).", markdown.length());
            context = context.addVirtualFragment(newFrag);
            return "Created Task Notes and added the note.";
        }
    }

    // --- Helper Methods ---

    /**
     * Fetches content from a given URL. Public static for reuse.
     *
     * @param url The URL to fetch from.
     * @return The content as a String.
     * @throws IOException If fetching fails.
     */
    public static String fetchUrlContent(URI url) throws IOException {
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

    private java.util.Set<ProjectFile> currentWorkspaceFiles() {
        return context.fileFragments()
                .filter(f -> f.getType() == ContextFragment.FragmentType.PROJECT_PATH)
                .flatMap(f -> f.files().stream())
                .collect(Collectors.toSet());
    }

    private IAnalyzer getAnalyzer() {
        return context.getContextManager().getAnalyzerUninterrupted();
    }
}
