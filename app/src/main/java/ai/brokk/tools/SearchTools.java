package ai.brokk.tools;

import static ai.brokk.project.FileFilteringService.toUnixPath;

import ai.brokk.AnalyzerUtil;
import ai.brokk.Completions;
import ai.brokk.ContextManager;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragments;
import ai.brokk.git.CommitInfo;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.git.IGitRepo;
import ai.brokk.project.AbstractProject;
import ai.brokk.util.Messages;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.Nullable;

/**
 * Contains tool implementations related to code analysis and searching, designed to be registered with the
 * ToolRegistry.
 */
public class SearchTools {
    private static final Logger logger = LogManager.getLogger(SearchTools.class);

    private final IContextManager contextManager; // Needed for file operations

    public SearchTools(IContextManager contextManager) {
        this.contextManager = contextManager;
    }

    // --- Sanitization Helper Methods
    // These methods strip trailing parentheses like "(params)" from symbol strings.
    // This is necessary because LLMs may incorrectly include them, but the underlying
    // code analysis tools expect clean FQNs or symbol names without parameter lists.

    private static String stripParams(String sym) {
        // Remove trailing (...) if it looks like a parameter list
        return sym.replaceAll("(?<=\\w)\\([^)]*\\)$", "");
    }

    private static List<String> stripParams(List<String> syms) {
        return syms.stream().map(SearchTools::stripParams).toList();
    }

    private IAnalyzer getAnalyzer() {
        return contextManager.getAnalyzerUninterrupted();
    }

    // --- Helper Methods

    /**
     * Build predicates for each supplied pattern. • If the pattern is a valid regex, the predicate performs
     * {@code matcher.find()}. • If the pattern is an invalid regex, the predicate falls back to
     * {@code String.contains()}.
     */
    private static List<Predicate<String>> compilePatternsWithFallback(List<String> patterns) {
        List<Predicate<String>> predicates = new ArrayList<>();
        for (String pat : patterns) {
            if (pat.isBlank()) {
                continue;
            }
            try {
                Pattern regex = Pattern.compile(pat);
                predicates.add(s -> regex.matcher(s).find());

                // Also handle the common "double-escaped dot" case (e.g. .*\\\\.java)
                if (pat.contains("\\\\.")) {
                    String singleEscaped = pat.replaceAll("\\\\\\\\.", "\\\\.");
                    if (!singleEscaped.equals(pat)) {
                        try {
                            Pattern alt = Pattern.compile(singleEscaped);
                            predicates.add(s -> alt.matcher(s).find());
                        } catch (PatternSyntaxException ignored) {
                            // If even the alternative is invalid we silently ignore it.
                        }
                    }
                }
            } catch (PatternSyntaxException ex) {
                // Fallback: simple substring match, but normalize to forward slashes
                predicates.add(s -> s.contains(toUnixPath(pat)));
            }
        }
        return predicates;
    }

    @Tool(
            """
                    Retrieves summaries (class fields and method/function signatures) for all classes and top-level functions defined within specified project files.
                    Supports glob patterns: '*' matches files in a single directory, '**' matches files recursively.
                    This is a fast and efficient way to read multiple related files at once.
                    (But if you don't know where what you want is located, you should use searchSymbols instead.)
                    """)
    public String getFileSummaries(
            @P(
                            "List of file paths relative to the project root. Supports glob patterns (* for single directory, ** for recursive). E.g., ['src/main/java/com/example/util/*.java', 'tests/foo/**.py']")
                    List<String> filePaths) {
        if (filePaths.isEmpty()) {
            return "Cannot get summaries: file paths list is empty";
        }

        var project = contextManager.getProject();
        List<ProjectFile> projectFiles = filePaths.stream()
                .flatMap(pattern -> Completions.expandPath(project, pattern).stream())
                .filter(ProjectFile.class::isInstance)
                .map(ProjectFile.class::cast)
                .distinct()
                .sorted() // Sort for deterministic output order
                .toList();

        if (projectFiles.isEmpty()) {
            return "No project files found matching the provided patterns: " + String.join(", ", filePaths);
        }

        List<String> allSkeletons = new ArrayList<>();
        List<String> filesProcessed = new ArrayList<>(); // Still useful for the "not found" message
        for (var file : projectFiles) {
            var skeletonsInFile = getAnalyzer().getSkeletons(file);
            if (!skeletonsInFile.isEmpty()) {
                // Add all skeleton strings from this file to the list
                allSkeletons.addAll(skeletonsInFile.values());
                filesProcessed.add(file.toString());
            } else {
                logger.debug("No skeletons found in file: {}", file);
            }
        }

        if (allSkeletons.isEmpty()) {
            // filesProcessed will be empty if no skeletons were found in any matched file
            var processedFilesString = filesProcessed.isEmpty()
                    ? projectFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(", "))
                    : String.join(", ", filesProcessed);
            return "No class summaries found in the matched files: " + processedFilesString;
        }

        // Return the combined skeleton strings directly, joined by newlines
        return String.join("\n\n", allSkeletons);
    }

    // --- Tool Methods requiring analyzer

    @Tool(
            """
            Search for symbols (class/function/field/module definitions) using static analysis.
            ONLY returns symbol definitions (declarations).
            DO NOT use for usages/call sites/instantiation/access patterns — use scanUsages or searchSubstrings.
            Output is grouped by file, then by symbol kind within each file.

            - kinds: CLASS, FUNCTION, FIELD, MODULE
            - FUNCTION may represent a member/instance/static method or a free/top-level function (varies by language/analyzer)
            - FIELD may represent a class/instance/static field or a top-level/module/global variable (varies by language/analyzer)
            - empty kind sections are omitted

            Examples:
            <file path="src/main/java/com/example/Foo.java">
            [CLASS]
            - com.example.Foo
            [FUNCTION]
            - com.example.Foo.bar
            </file>
            """)
    public String searchSymbols(
            @P(
                            "Case-insensitive regex patterns to search for code symbols. Since ^ and $ are implicitly included, YOU MUST use explicit wildcarding (e.g., .*Foo.*, Abstract.*, [a-z]*DAO) unless you really want exact matches.")
                    List<String> patterns,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning,
            @P("Include test files in results. Default: false.") boolean includeTests) {
        // Sanitize patterns: LLM might add `()` to symbols, Joern regex usually doesn't want that unless intentional.
        patterns = stripParams(patterns);
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search definitions: patterns list is empty");
        }
        if (reasoning.isBlank()) {
            // Tolerate missing reasoning for now, maybe make mandatory later
            logger.warn("Missing reasoning for searchSymbols call");
        }

        var analyzer = getAnalyzer();
        Set<CodeUnit> allDefinitions = new HashSet<>();
        for (String pattern : patterns) {
            if (!pattern.isBlank()) {
                allDefinitions.addAll(analyzer.searchDefinitions(pattern));
            }
        }
        logger.trace("Raw definitions: {}", allDefinitions);

        if (!includeTests) {
            allDefinitions = allDefinitions.stream()
                    .filter(cu -> !ContextManager.isTestFile(cu.source(), analyzer))
                    .collect(Collectors.toSet());
        }

        if (allDefinitions.isEmpty()) {
            return "No definitions found for patterns: " + String.join(", ", patterns);
        }

        // Group by file, then by kind within each file
        var fileGroups = allDefinitions.stream()
                .collect(Collectors.groupingBy(
                        cu -> toUnixPath(cu.source().toString()),
                        Collectors.groupingBy(cu -> cu.kind().name())));

        // Build output: sorted files, sorted kinds per file, sorted symbols per kind
        var result = new StringBuilder();
        fileGroups.entrySet().stream()
                .sorted((a, b) -> a.getKey().compareTo(b.getKey()))
                .forEach(fileEntry -> {
                    var filePath = fileEntry.getKey();
                    var kindGroups = fileEntry.getValue();

                    result.append("<file path=\"").append(filePath).append("\">\n");

                    // Emit kind sections in a stable order based on analyzer's CodeUnitType
                    var kindOrder = List.of("CLASS", "FUNCTION", "FIELD", "MODULE");
                    kindOrder.forEach(kind -> {
                        var symbols = kindGroups.get(kind);
                        if (symbols != null && !symbols.isEmpty()) {
                            result.append("[").append(kind).append("]\n");
                            symbols.stream()
                                    .map(CodeUnit::fqName)
                                    .distinct()
                                    .sorted()
                                    .forEach(fqn ->
                                            result.append("- ").append(fqn).append("\n"));
                        }
                    });

                    result.append("</file>\n");
                });

        return result.toString();
    }

    @Tool(
            """
            Returns the call sites where symbols are used and three examples of full call site source. Use this to discover how classes, methods, or fields are actually used throughout the codebase.
            Use this for questions like “how is X used/accessed/obtained/wired”.
            If you don’t know the fully qualified symbol name, call searchSymbols once to get it.
            """)
    public String scanUsages(
            @P("Fully qualified symbol names (package name, class name, optional member name) to find usages for")
                    List<String> symbols,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning,
            @P("Include call sites in test files in results.") boolean includeTests) {
        // Sanitize symbols: remove potential `(params)` suffix from LLM.
        symbols = stripParams(symbols);
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("Cannot search usages: symbols list is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for getUsages call");
        }

        List<String> results = new ArrayList<>();
        for (String symbol : symbols) {
            if (symbol.isBlank()) continue;

            var fragment = new ContextFragments.UsageFragment(
                    contextManager, symbol, includeTests, ContextFragments.UsageMode.SAMPLE);
            String text = fragment.text().join();
            if (!text.isEmpty()) {
                results.add(text);
            }
        }

        if (results.isEmpty()) {
            return "No usages found for: " + String.join(", ", symbols);
        }

        return String.join("\n\n", results);
    }

    @Tool(
            """
                    Returns a summary of classes' contents, including fields and method signatures.
                    Use this to understand class structures and APIs much faster than fetching full source code.
                    """)
    public String getClassSkeletons(
            @P("Fully qualified class names to get the skeleton structures for") List<String> classNames) {
        // Sanitize classNames: remove potential `(params)` suffix from LLM.
        classNames = stripParams(classNames);
        if (classNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get skeletons: class names list is empty");
        }

        var result = classNames.stream()
                .distinct()
                .map(fqcn -> AnalyzerUtil.getSkeleton(getAnalyzer(), fqcn))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.joining("\n\n"));

        if (result.isEmpty()) {
            return "No classes found in: " + String.join(", ", classNames);
        }

        return result;
    }

    @Tool(
            """
                    Returns the full source code of classes.
                    This is expensive, so prefer requesting skeletons or method sources when possible.
                    Use this when you need the complete implementation details, or if you think multiple methods in the classes may be relevant.
                    """)
    public String getClassSources(
            @P("Fully qualified class names to retrieve the full source code for") List<String> classNames) {
        // Sanitize classNames: remove potential `(params)` suffix from LLM.
        classNames = stripParams(classNames);
        if (classNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get class sources: class names list is empty");
        }

        StringBuilder result = new StringBuilder();
        Set<String> added = new HashSet<>();

        var analyzer = getAnalyzer();
        classNames.stream().distinct().filter(s -> !s.isBlank()).forEach(className -> {
            var cuOpt = analyzer.getDefinitions(className).stream()
                    .filter(CodeUnit::isClass)
                    .findFirst();
            if (cuOpt.isPresent()) {
                var cu = cuOpt.get();
                if (added.add(cu.fqName())) {
                    var fragment = new ContextFragments.CodeFragment(contextManager, cu);
                    var text = fragment.text().join();
                    if (!text.isEmpty()) {
                        if (!result.isEmpty()) {
                            result.append("\n\n");
                        }
                        result.append(text);
                    }
                }
            }
        });

        if (result.isEmpty()) {
            return "No sources found for classes: " + String.join(", ", classNames);
        }

        return result.toString();
    }

    @Tool(
            """
                    Returns the file paths (relative to the project root) where the specified symbols are defined.
                    Use this to locate where interesting symbols live,
                    then use getFileSummaries to get an overview of those files.
                    Accepts all symbol types: classes, methods, fields, and modules.
                    """)
    public String getSymbolLocations(
            @P("Fully qualified symbol names to locate (classes, methods, fields, or modules)") List<String> symbols) {
        // Sanitize symbols: remove potential `(params)` suffix from LLM.
        symbols = stripParams(symbols);
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("Cannot get symbol locations: symbols list is empty");
        }

        var analyzer = getAnalyzer();
        List<String> locationMappings = new ArrayList<>();
        List<String> notFound = new ArrayList<>();

        symbols.stream().distinct().filter(s -> !s.isBlank()).forEach(symbol -> {
            var cuOpt = analyzer.getDefinitions(symbol).stream().findFirst();
            if (cuOpt.isPresent()) {
                var cu = cuOpt.get();
                var filepath = cu.source().toString();
                locationMappings.add(symbol + " -> " + filepath);
            } else {
                notFound.add(symbol);
            }
        });

        StringBuilder result = new StringBuilder();
        result.append(String.join("\n", locationMappings));

        if (!notFound.isEmpty()) {
            result.append("\n\nNot found: ").append(String.join(", ", notFound));
        }

        return result.toString();
    }

    @Tool(
            """
                    Returns the full source code of specific methods or functions. Use this to examine the implementation of particular methods without retrieving the entire classes.
                    Note: Depending on the language/analyzer, "function" may represent either a member method or a free/top-level function.
                    """)
    public String getMethodSources(
            @P("Fully qualified method names (package name, class name, method name) to retrieve sources for")
                    List<String> methodNames) {
        // Sanitize methodNames: remove potential `(params)` suffix from LLM.
        methodNames = stripParams(methodNames);
        if (methodNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get method sources: method names list is empty");
        }

        StringBuilder result = new StringBuilder();
        Set<String> added = new HashSet<>();

        var analyzer = getAnalyzer();
        methodNames.stream().distinct().filter(s -> !s.isBlank()).forEach(methodName -> {
            var cuOpt = analyzer.getDefinitions(methodName).stream()
                    .filter(CodeUnit::isFunction)
                    .findFirst();
            if (cuOpt.isPresent()) {
                var cu = cuOpt.get();
                if (added.add(cu.fqName())) {
                    Set<String> sources = analyzer.getSources(cu, true);
                    if (!sources.isEmpty()) {
                        if (!result.isEmpty()) {
                            result.append("\n\n");
                        }
                        result.append(String.join("\n\n", sources));
                    }
                }
            }
        });

        if (result.isEmpty()) {
            return "No sources found for methods: " + String.join(", ", methodNames);
        }

        return result.toString();
    }

    @Tool(
            """
            Retrieves the git commit log for a file or directory path, showing the history of changes.
            Provides short commit hash, author, date, commit message, and file list for each entry.
            Tracks file renames to help follow code evolution across different filenames.
            Use an empty path to get the repository-wide commit log.
            """)
    public String getGitLog(
            @P("File or directory path relative to the project root. Use empty string for repository-wide log.")
                    String path,
            @P("Maximum number of log entries to return (capped at 100).") int limit,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning) {
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for getGitLog call");
        }

        var repo = contextManager.getRepo();

        // Cap limit at 100 and ensure at least 1
        int effectiveLimit = Math.max(1, Math.min(limit, 100));

        // Canonicalize: normalize the path, treat blank as empty
        String canonicalPathString = path.isBlank() ? "" : path.strip();
        if (!canonicalPathString.isEmpty()) {
            canonicalPathString =
                    Path.of(canonicalPathString).normalize().toString().replace('\\', '/');
        }

        try {
            // Check if it's a file for rename tracking
            boolean isFile = false;
            if (!canonicalPathString.isEmpty()) {
                var projectFile = new ProjectFile(contextManager.getProject().getRoot(), Path.of(canonicalPathString));
                isFile = java.nio.file.Files.isRegularFile(projectFile.absPath())
                        || repo.getTrackedFiles().contains(projectFile);
            }

            var sb = new StringBuilder();
            sb.append("<git_log");
            if (!canonicalPathString.isEmpty()) {
                sb.append(" path=\"").append(canonicalPathString).append("\"");
            }
            sb.append(">\n");

            if (isFile) {
                List<IGitRepo.FileHistoryEntry> entries = repo instanceof GitRepo gr
                        ? gr.getFileHistoryWithPaths(
                                new ProjectFile(contextManager.getProject().getRoot(), Path.of(canonicalPathString)))
                        : List.of();

                if (entries.isEmpty()) {
                    return "No history found for file: " + canonicalPathString;
                }

                ProjectFile previousPath = null;
                for (var entry : entries.stream().limit(effectiveLimit).toList()) {
                    appendCommitEntry(sb, repo, entry.commit(), entry.path(), previousPath);
                    previousPath = entry.path();
                }
            } else {
                var commits = repo.getGitLog(canonicalPathString, effectiveLimit);
                if (commits.isEmpty()) {
                    return "No history found for path: "
                            + (canonicalPathString.isEmpty() ? "(repo root)" : canonicalPathString);
                }

                for (var commit : commits) {
                    appendCommitEntry(sb, repo, commit, null, null);
                }
            }

            sb.append("</git_log>");
            return sb.toString();
        } catch (GitAPIException e) {
            logger.error("Error retrieving git log for path '{}': {}", path, e.getMessage(), e);
            return "Error retrieving git log: " + e.getMessage();
        }
    }

    private void appendCommitEntry(
            StringBuilder sb,
            IGitRepo repo,
            CommitInfo commit,
            @Nullable ProjectFile currentPath,
            @Nullable ProjectFile nextPath) {
        var shortId = (repo instanceof GitRepo gr)
                ? gr.shortHash(commit.id())
                : commit.id().substring(0, 7);

        String fullMessage;
        try {
            fullMessage = (repo instanceof GitRepo gr) ? gr.getCommitFullMessage(commit.id()) : commit.message();
        } catch (GitAPIException e) {
            fullMessage = commit.message();
        }

        sb.append("<entry hash=\"").append(shortId).append("\"");
        sb.append(" author=\"").append(commit.author()).append("\"");
        sb.append(" date=\"").append(commit.date()).append("\"");
        if (currentPath != null) {
            sb.append(" path=\"").append(currentPath).append("\"");
        }
        sb.append(">\n");

        if (nextPath != null && !nextPath.equals(currentPath)) {
            sb.append("[RENAMED] ")
                    .append(currentPath)
                    .append(" -> ")
                    .append(nextPath)
                    .append("\n");
        }

        sb.append(fullMessage.strip()).append("\n");

        List<ProjectFile> changedFiles;
        try {
            changedFiles = CommitInfo.changedFiles((GitRepo) repo, commit.id());
        } catch (GitAPIException e) {
            logger.error("Error retrieving changed files for commit {}", commit.id(), e);
            changedFiles = List.of();
        }

        if (!changedFiles.isEmpty()) {
            String fileCdl = changedFiles.stream()
                    .map(ProjectFile::getFileName)
                    .distinct()
                    .sorted()
                    .collect(Collectors.joining(", "));
            sb.append("Files: ").append(fileCdl).append("\n");
        }

        sb.append("</entry>\n");
    }

    @Tool(
            """
                    Search git commit messages using a Java regular expression.
                    Returns matching commits with their message and list of changed files.
                    If the list of files is extremely long, it will be summarized with respect to your explanation.
                    """)
    public String searchGitCommitMessages(
            @P("Java-style regex pattern to search for within commit messages.") String pattern,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning) {
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("Cannot search commit messages: pattern is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for searchGitCommitMessages call");
        }

        var projectRoot = contextManager.getProject().getRoot();
        if (!GitRepoFactory.hasGitRepo(projectRoot)) {
            return "Cannot search commit messages: Git repository not found for this project.";
        }

        var repo = contextManager.getRepo();
        if (!(repo instanceof GitRepo gitRepo)) {
            return "Cannot search commit messages: git repo is not available as a GitRepo (was: "
                    + repo.getClass().getName() + ").";
        }

        List<CommitInfo> matchingCommits;
        try {
            matchingCommits = gitRepo.searchCommits(pattern);
        } catch (GitAPIException e) {
            logger.error("Error searching commit messages", e);
            return "Error searching commit messages: " + e.getMessage();
        }

        if (matchingCommits.isEmpty()) {
            return "No commit messages found matching pattern: " + pattern;
        }

        StringBuilder resultBuilder = new StringBuilder();
        for (var commit : matchingCommits) {
            resultBuilder.append("<commit id=\"").append(commit.id()).append("\">\n");
            try {
                // Ensure we always close <message>
                resultBuilder.append("<message>\n");
                try {
                    resultBuilder.append(commit.message().stripIndent()).append("\n");
                } finally {
                    resultBuilder.append("</message>\n");
                }

                // Ensure we always close <edited_files>
                resultBuilder.append("<edited_files>\n");
                try {
                    List<ProjectFile> changedFilesList;
                    try {
                        changedFilesList = CommitInfo.changedFiles(gitRepo, commit.id());
                    } catch (GitAPIException e) {
                        logger.error("Error retrieving changed files for commit {}", commit.id(), e);
                        changedFilesList = List.of();
                    }
                    var changedFiles =
                            changedFilesList.stream().map(ProjectFile::toString).collect(Collectors.joining("\n"));
                    resultBuilder.append(changedFiles).append("\n");
                } finally {
                    resultBuilder.append("</edited_files>\n");
                }
            } finally {
                resultBuilder.append("</commit>\n");
            }
        }

        return resultBuilder.toString();
    }

    // --- Text search tools

    @Tool(
            """
                    Returns file names (paths relative to the project root) whose text contents match Java regular expression patterns.
                    This is slower than searchSymbols but can find references to external dependencies and comment strings.
                    """)
    public String searchSubstrings(
            @P(
                            "Java-style regex patterns to search for within file contents. Unlike searchSymbols this does not automatically include any implicit anchors or case insensitivity.")
                    List<String> patterns,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning) {
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search substrings: patterns list is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for searchSubstrings call");
        }

        logger.debug("Searching file contents for patterns: {}", patterns);

        List<Predicate<String>> predicates = compilePatternsWithFallback(patterns);
        if (predicates.isEmpty()) {
            throw new IllegalArgumentException("No valid patterns provided");
        }

        var matchingFilenames = searchSubstrings(
                        patterns, contextManager.getProject().getAllFiles())
                .stream()
                .map(ProjectFile::toString)
                .collect(Collectors.toSet());

        if (matchingFilenames.isEmpty()) {
            return "No files found with content matching patterns: " + String.join(", ", patterns);
        }

        var msg = "Files with content matching patterns: " + String.join(", ", matchingFilenames);
        logger.debug(msg);
        return msg;
    }

    public static Set<ProjectFile> searchSubstrings(List<String> patterns, Set<ProjectFile> filesToSearch) {
        List<Predicate<String>> predicates = compilePatternsWithFallback(patterns);
        if (predicates.isEmpty()) {
            return Set.of();
        }

        return filesToSearch.parallelStream()
                .map(file -> {
                    if (!file.isText()) {
                        return null;
                    }
                    var fileContentsOpt = file.read();
                    if (fileContentsOpt.isEmpty()) {
                        return null;
                    }
                    String fileContents = fileContentsOpt.get();

                    for (Predicate<String> predicate : predicates) {
                        if (predicate.test(fileContents)) {
                            return file;
                        }
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    @Tool(
            """
                    Returns filenames (relative to the project root) that match the given Java regular expression patterns.
                    Use this to find configuration files, test data, or source files when you know part of their name.
                    """)
    public String searchFilenames(
            @P("Java-style regex patterns to match against filenames.") List<String> patterns,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning) {
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search filenames: patterns list is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for searchFilenames call");
        }

        logger.debug("Searching filenames for patterns: {}", patterns);

        List<Predicate<String>> predicates = compilePatternsWithFallback(patterns);
        if (predicates.isEmpty()) {
            throw new IllegalArgumentException("No valid patterns provided");
        }

        var matchingFiles = contextManager.getProject().getAllFiles().stream()
                .map(ProjectFile::toString) // Use relative path from ProjectFile
                .filter(filePath -> {
                    // Normalise to forward slashes so regex like "frontend-mop/.*\\.svelte"
                    // work on Windows paths containing back-slashes.
                    String unixPath = toUnixPath(filePath);
                    for (Predicate<String> predicate : predicates) {
                        if (predicate.test(unixPath)) {
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toList());

        if (matchingFiles.isEmpty()) {
            return "No filenames found matching patterns: " + String.join(", ", patterns);
        }

        return "Matching filenames: " + String.join(", ", matchingFiles);
    }

    @Tool(
            """
                    Returns the full contents of the specified files. Use this after searchFilenames, searchSubstrings or searchSymbols or when you need the content of a non-code file.
                    This can be expensive for large files.
                    """)
    public String getFileContents(
            @P("List of filenames (relative to project root) to retrieve contents for.") List<String> filenames) {
        if (filenames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get file contents: filenames list is empty");
        }

        logger.debug("Getting contents for files: {}", filenames);

        StringBuilder result = new StringBuilder();
        boolean anySuccess = false;

        for (String filename : filenames.stream().distinct().toList()) {
            try {
                var file = contextManager.toFile(filename); // Use contextManager
                if (!file.exists()) {
                    logger.debug("File not found or not a regular file: {}", file);
                    continue;
                }
                var contentOpt = file.read();
                if (contentOpt.isEmpty()) {
                    logger.debug("Skipping unreadable file: {}", filename);
                    continue;
                }
                var content = contentOpt.get();
                if (result.length() > 0) {
                    result.append("\n\n");
                }
                result.append(
                        """
                                ```%s
                                %s
                                ```
                                """
                                .stripIndent()
                                .formatted(filename, content));
                anySuccess = true;
            } catch (Exception e) {
                logger.error("Unexpected error getting content for {}: {}", filename, e.getMessage());
                // Continue to next file
            }
        }

        if (!anySuccess) {
            return "None of the requested files could be read: " + String.join(", ", filenames);
        }

        return result.toString();
    }

    /**
     * Formats files in a directory as a comma-separated string.
     * Public static to allow reuse by BuildAgent.
     */
    public static String formatFilesInDirectory(
            Collection<ProjectFile> allFiles, Path normalizedDirectoryPath, String originalDirectoryPath) {
        var files = allFiles.stream()
                .parallel()
                .filter(file -> file.getParent().equals(normalizedDirectoryPath))
                .sorted()
                .map(ProjectFile::toString)
                .collect(Collectors.joining(", "));

        if (files.isEmpty()) {
            return "No files found in directory: " + originalDirectoryPath;
        }

        return "Files in " + originalDirectoryPath + ": " + files;
    }

    // Only includes project files. Is this what we want?
    @Tool(
            """
                    Lists files within a specified directory relative to the project root.
                    Use '.' for the root directory.
                    """)
    public String listFiles(
            @P("Directory path relative to the project root (e.g., '.', 'src/main/java')") String directoryPath) {
        if (directoryPath.isBlank()) {
            throw new IllegalArgumentException("Directory path cannot be empty");
        }

        // Normalize path for filtering (remove leading/trailing slashes, handle '.')
        var normalizedPath = Path.of(directoryPath).normalize();

        logger.debug("Listing files for directory path: '{}' (normalized to `{}`)", directoryPath, normalizedPath);

        return formatFilesInDirectory(contextManager.getProject().getAllFiles(), normalizedPath, directoryPath);
    }

    @Tool(
            """
                    Returns a hierarchical "bag of identifiers" summary for all files within a specified directory.
                    This provides a quick overview of class members and nested structures by listing names only;
                    it is significantly less detailed than getFileSummaries as it omits full signatures and field types.
                    Subdirectories are listed at the top.
                    If the summary of all files is too large, it returns only the file and directory names.
                    """)
    public String skimDirectory(
            @P("Directory path relative to the project root (e.g., '.', 'src/main/java').") String directoryPath,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
                    String reasoning) {
        if (directoryPath.isBlank()) {
            throw new IllegalArgumentException("Directory path cannot be empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for skimDirectory call");
        }

        var project = contextManager.getProject();
        var analyzer = getAnalyzer();
        var targetDir = Path.of(directoryPath).normalize();

        // Check if we're inside the dependencies directory - don't filter by gitignore there
        Path dependenciesPath = Path.of(AbstractProject.BROKK_DIR, AbstractProject.DEPENDENCIES_DIR);
        boolean isInDependencies = targetDir.startsWith(dependenciesPath);

        Path absTargetDir = project.getRoot().resolve(targetDir);
        File[] fsItems = absTargetDir.toFile().listFiles();

        if (fsItems == null || fsItems.length == 0) {
            return "No files or directories found in: " + directoryPath;
        }

        List<String> subDirs = new ArrayList<>();
        List<ProjectFile> children = new ArrayList<>();

        for (File item : fsItems) {
            String name = item.getName();
            if (!isInDependencies && project.isGitignored(targetDir.resolve(name))) {
                continue;
            }
            if (item.isDirectory()) {
                subDirs.add(name + "/");
            } else {
                children.add(new ProjectFile(project.getRoot(), targetDir.resolve(name)));
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!subDirs.isEmpty()) {
            sb.append("<subdirectories>\n  ")
                    .append(subDirs.stream().sorted().collect(Collectors.joining(", ")))
                    .append("\n</subdirectories>\n\n");
        }

        int totalTokens = Messages.getApproximateTokens(sb.toString());
        int maxTokens = 12800; // ~10% of 128k
        boolean tooLarge = false;

        StringBuilder fileSummaries = new StringBuilder();
        children.sort(ProjectFile::compareTo);
        for (var file : children) {
            String identifiers = analyzer.summarizeSymbols(file);
            String content = identifiers.isBlank() ? "- (no symbols found)" : identifiers;
            String fileBlock = "<file path=\"" + file.toString().replace('\\', '/') + "\">\n" + content + "\n</file>\n";

            totalTokens += Messages.getApproximateTokens(fileBlock);
            if (totalTokens > maxTokens) {
                tooLarge = true;
                break;
            }
            fileSummaries.append(fileBlock);
        }

        if (tooLarge) {
            String fileList = children.stream().map(ProjectFile::getFileName).collect(Collectors.joining("\n"));
            return sb.append("The directory summary is too large. Files:\n")
                    .append(fileList)
                    .toString();
        }

        if (fileSummaries.isEmpty() && subDirs.isEmpty()) {
            return "No files or directories found in: " + directoryPath;
        }

        return sb.append(fileSummaries).toString();
    }
}
