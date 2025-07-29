package io.github.jbellis.brokk.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.jbellis.brokk.AnalyzerUtil;
import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Contains tool implementations related to code analysis and searching,
 * designed to be registered with the ToolRegistry.
 */
public class SearchTools {
    /**
     * Record representing compressed symbols with their common prefix.
     */
    public record CompressedSymbols(String prefix, List<String> symbols) {}

    private static final Logger logger = LogManager.getLogger(SearchTools.class);

    private final IContextManager contextManager; // Needed for file operations

    public SearchTools(IContextManager contextManager) {
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager cannot be null");
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
        return syms.stream()
                   .map(SearchTools::stripParams)
                   .toList();
    }
    
    private IAnalyzer getAnalyzer() {
        return contextManager.getAnalyzerUninterrupted();
    }

    // --- Helper Methods

    /**
     * Compresses a list of fully qualified symbol names by finding the longest common package prefix
     * and removing it from each symbol.
     * @param symbols A list of fully qualified symbol names
     * @return A tuple containing: 1) the common package prefix, 2) the list of compressed symbol names
     */
    public static CompressedSymbols compressSymbolsWithPackagePrefix(List<String> symbols) {
        List<String[]> packageParts = symbols.stream()
                .filter(Objects::nonNull) // Filter nulls just in case
                .map(s -> s.split("\\."))
                .filter(arr -> arr.length > 0) // Ensure split resulted in something
                .toList();

        if (packageParts.isEmpty()) {
            return new CompressedSymbols("", List.of());
        }

        String[] firstParts = packageParts.getFirst();
        int maxPrefixLength = 0;

        for (int i = 0; i < firstParts.length - 1; i++) { // Stop before last part (class/method)
            boolean allMatch = true;
            for (String[] parts : packageParts) {
                // Ensure current part exists and matches
                if (i >= parts.length - 1 || !parts[i].equals(firstParts[i])) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                maxPrefixLength = i + 1;
            } else {
                break;
            }
        }

        if (maxPrefixLength > 0) {
            String commonPrefix = String.join(".", Arrays.copyOfRange(firstParts, 0, maxPrefixLength)) + ".";
            List<String> compressedSymbols = symbols.stream()
                    .map(s -> s.startsWith(commonPrefix) ? s.substring(commonPrefix.length()) : s)
                    .collect(Collectors.toList());
            return new CompressedSymbols(commonPrefix, compressedSymbols);
        }

        return new CompressedSymbols("", symbols); // Return original list if no common prefix
    }

    /**
     * Formats a list of symbols with prefix compression if applicable.
     * @param label The label to use in the output (e.g., "Relevant symbols", "Related classes")
     * @param symbols The list of symbols to format
     * @return A formatted string with compressed symbols if possible
     */
    private String formatCompressedSymbols(String label, List<String> symbols) {
        if (symbols.isEmpty()) {
            return label + ": None found";
        }

        var compressionResult = compressSymbolsWithPackagePrefix(symbols);
        String commonPrefix = compressionResult.prefix();
        List<String> compressedSymbols = compressionResult.symbols();

        if (commonPrefix.isEmpty()) {
            // Sort for consistent output when no compression happens
            return label + ": " + symbols.stream().sorted().collect(Collectors.joining(", "));
        }

        // Sort compressed symbols too
        return "%s: [Common package prefix: '%s'. IMPORTANT: you MUST use full symbol names including this prefix for subsequent tool calls] %s"
                .formatted(label, commonPrefix, compressedSymbols.stream().sorted().collect(Collectors.joining(", ")));
    }

    // Internal helper for formatting the compressed string. Public static.
    public static String formatCompressedSymbolsInternal(String label, List<String> compressedSymbols, String commonPrefix) {
        if (commonPrefix.isEmpty()) {
            // Sort for consistent output when no compression happens
            return label + ": " + compressedSymbols.stream().sorted().collect(Collectors.joining(", "));
        }
        // Sort compressed symbols too
        return "%s: [Common package prefix: '%s'. IMPORTANT: you MUST use full symbol names including this prefix for subsequent tool calls] %s"
                .formatted(label, commonPrefix, compressedSymbols.stream().sorted().collect(Collectors.joining(", ")));
    }

    @Tool(value = """
    Retrieves summaries (fields and method signatures) for all classes defined within specified project files.
    Supports glob patterns: '*' matches files in a single directory, '**' matches files recursively.
    This is a fast and efficient way to read multiple related files at once.
    (But if you don't know where what you want is located, you should use searchSymbols instead.)
    """)
    public String getFileSummaries(
            @P("List of file paths relative to the project root. Supports glob patterns (* for single directory, ** for recursive). E.g., ['src/main/java/com/example/util/*.java', 'tests/foo/**.py']")
            List<String> filePaths
    ) {
        assert getAnalyzer().isCpg() : "Cannot get summaries: Code Intelligence is not available.";
        if (filePaths.isEmpty()) {
            return "Cannot get summaries: file paths list is empty";
        }

        var analyzer = getAnalyzer();
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
            var skeletonsInFile = analyzer.getSkeletons(file);
            if (!skeletonsInFile.isEmpty()) {
                // Add all skeleton strings from this file to the list
                skeletonsInFile.values().forEach(allSkeletons::add);
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

    @Tool(value = """
    Search for symbols (class/method/field definitions) using Joern.
    This should usually be the first step in a search.
    """)
    public String searchSymbols(
            @P("Case-insensitive Joern regex patterns to search for code symbols. Since ^ and $ are implicitly included, YOU MUST use explicit wildcarding (e.g., .*Foo.*, Abstract.*, [a-z]*DAO) unless you really want exact matches.")
            List<String> patterns,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
            String reasoning
    ) {
        assert getAnalyzer().isCpg() : "Cannot search definitions: CPG analyzer is not available.";
        // Sanitize patterns: LLM might add `()` to symbols, Joern regex usually doesn't want that unless intentional.
        patterns = stripParams(patterns);
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search definitions: patterns list is empty");
        }
        if (reasoning.isBlank()) {
            // Tolerate missing reasoning for now, maybe make mandatory later
            logger.warn("Missing reasoning for searchSymbols call");
        }

        Set<CodeUnit> allDefinitions = new HashSet<>();
        for (String pattern : patterns) {
            if (!pattern.isBlank()) {
                allDefinitions.addAll(getAnalyzer().searchDefinitions(pattern));
            }
        }
        logger.debug("Raw definitions: {}", allDefinitions);

        if (allDefinitions.isEmpty()) {
            return "No definitions found for patterns: " + String.join(", ", patterns);
        }

        var references = allDefinitions.stream()
                .map(CodeUnit::fqName)
                .distinct() // Ensure uniqueness
                .sorted()   // Consistent order
                .toList();

        return String.join(", ", references);
    }

    @Tool(value = """
    Returns the source code of blocks where symbols are used. Use this to discover how classes, methods, or fields are actually used throughout the codebase.
    """)
    public String getUsages(
            @P("Fully qualified symbol names (package name, class name, optional member name) to find usages for")
            List<String> symbols,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
            String reasoning
    ) {
        assert getAnalyzer().isCpg() : "Cannot search usages: CPG analyzer is not available.";
        // Sanitize symbols: remove potential `(params)` suffix from LLM.
        symbols = stripParams(symbols);
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("Cannot search usages: symbols list is empty");
        }
        if (reasoning.isBlank()) {
             logger.warn("Missing reasoning for getUsages call");
        }

        List<CodeUnit> allUses = new ArrayList<>();
        for (String symbol : symbols) {
            if (!symbol.isBlank()) {
                allUses.addAll(getAnalyzer().getUses(symbol));
            }
        }

        if (allUses.isEmpty()) {
            return "No usages found for: " + String.join(", ", symbols);
        }

        var processedUsages = AnalyzerUtil.processUsages(getAnalyzer(), allUses).code();
        return "Usages of " + String.join(", ", symbols) + ":\n\n" + processedUsages;
    }

    @Tool(value = """
    Returns a list of related class names, ordered by relevance (using PageRank).
    Use this for exploring and also when you're almost done and want to double-check that you haven't missed anything.
    """)
    public String getRelatedClasses(
            @P("List of fully qualified class names to use as seeds for finding related classes.")
            List<String> classNames
    ) {
        assert getAnalyzer().isCpg() : "Cannot find related classes: CPG analyzer is not available.";
        // Sanitize classNames: remove potential `(params)` suffix from LLM.
        classNames = stripParams(classNames);
        if (classNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot search pagerank: classNames is empty");
        }

        // Create map of seeds from discovered units
        HashMap<String, Double> weightedSeeds = new HashMap<>();
        for (String className : classNames) {
            weightedSeeds.put(className, 1.0);
        }

        var pageRankUnits = AnalyzerUtil.combinedPagerankFor(getAnalyzer(), weightedSeeds);

        if (pageRankUnits.isEmpty()) {
            return "No related code found via PageRank for seeds: " + String.join(", ", classNames);
        }

        var pageRankResults = pageRankUnits.stream().limit(50).map(CodeUnit::fqName).toList();

        // Get skeletons for the top few results -- potentially saves a round trip for a few extra tokens
        var skResult = pageRankResults.stream().distinct()
                .limit(10) // padding in case of not defined
                .map(fqcn -> getAnalyzer().getSkeleton(fqcn))
                .filter(Optional::isPresent)
                .limit(5)
                .map(Optional::get)
                .collect(Collectors.joining("\n\n"));

        var formattedSkeletons = skResult.isEmpty() ? "" : "# Summaries of the top related classes: \n\n" + skResult + "\n\n";
        var formattedClassList = formatCompressedSymbols("# Full list of related classes, up to 50", pageRankResults);
        return formattedSkeletons + formattedClassList;
    }

    @Tool(value = """
    Returns an overview of classes' contents, including fields and method signatures.
    Use this to understand class structures and APIs much faster than fetching full source code.
    """)
    public String getClassSkeletons(
            @P("Fully qualified class names to get the skeleton structures for")
            List<String> classNames
    ) {
        assert getAnalyzer().isCpg() : "Cannot get skeletons: Code Intelligence is not available.";
        // Sanitize classNames: remove potential `(params)` suffix from LLM.
        classNames = stripParams(classNames);
        if (classNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get skeletons: class names list is empty");
        }

        var result = classNames.stream().distinct().map(fqcn -> getAnalyzer().getSkeleton(fqcn))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.joining("\n\n"));

        if (result.isEmpty()) {
            return "No classes found in: " + String.join(", ", classNames);
        }

        return result;
    }

    @Tool(value = """
    Returns the full source code of classes.
    This is expensive, so prefer requesting skeletons or method sources when possible.
    Use this when you need the complete implementation details, or if you think multiple methods in the classes may be relevant.
    """)
    public String getClassSources(
            @P("Fully qualified class names to retrieve the full source code for")
            List<String> classNames,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
            String reasoning
    ) {
        assert getAnalyzer().isCpg() : "Cannot get class sources: CPG analyzer is not available.";
        // Sanitize classNames: remove potential `(params)` suffix from LLM.
        classNames = stripParams(classNames);
        if (classNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get class sources: class names list is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for getClassSources call");
        }

        StringBuilder result = new StringBuilder();
        Set<String> processedSources = new HashSet<>(); // Avoid duplicates if multiple names map to same source

        for (String className : classNames) {
            if (!className.isBlank()) {
                var classSource = getAnalyzer().getClassSource(className);
                if (classSource != null) {
                     if (!classSource.isEmpty() && processedSources.add(classSource)) {
                         if (!result.isEmpty()) {
                             result.append("\n\n");
                         }
                          // Include filename from analyzer if possible
                          String filename = getAnalyzer().getFileFor(className).map(ProjectFile::toString).orElseGet(() -> "unknown file"); // Use orElseGet for Optional
                          result.append("Source code of ").append(className)
                                .append(" (from ").append(filename).append("):\n\n")
                                .append(classSource);
                    }
                }
            }
        }

        if (result.isEmpty()) {
            return "No sources found for classes: " + String.join(", ", classNames);
        }

        return result.toString();
    }

    @Tool(value = """
    Returns the full source code of specific methods. Use this to examine the implementation of particular methods without retrieving the entire classes.
    """)
    public String getMethodSources(
            @P("Fully qualified method names (package name, class name, method name) to retrieve sources for")
            List<String> methodNames
    ) {
         assert getAnalyzer().isCpg() : "Cannot get method sources: CPG analyzer is not available.";
        // Sanitize methodNames: remove potential `(params)` suffix from LLM.
        methodNames = stripParams(methodNames);
        if (methodNames.isEmpty()) {
            throw new IllegalArgumentException("Cannot get method sources: method names list is empty");
        }

        StringBuilder result = new StringBuilder();
        Set<String> processedMethodSources = new HashSet<>();

        for (String methodName : methodNames) {
            if (!methodName.isBlank()) {
                var methodSourceOpt = getAnalyzer().getMethodSource(methodName);
                if (methodSourceOpt.isPresent()) {
                    String methodSource = methodSourceOpt.get();
                    if (!processedMethodSources.contains(methodSource)) {
                        processedMethodSources.add(methodSource);
                        if (!result.isEmpty()) {
                            result.append("\n\n");
                        }
                        result.append("// Source for ").append(methodName).append("\n");
                        result.append(methodSource);
                    }
                }
            }
        }

        if (result.isEmpty()) {
            return "No sources found for methods: " + String.join(", ", methodNames);
        }

        return result.toString();
    }

    @Tool(value = """
    Returns the call graph to a depth of 5 showing which methods call the given method and one line of source code for each invocation.
    Use this to understand method dependencies and how code flows into a method.
    """)
    public String getCallGraphTo(
            @P("Fully qualified method name (package name, class name, method name) to find callers for")
            String methodName
    ) {
        assert getAnalyzer().isCpg() : "Cannot get call graph: CPG analyzer is not available.";
        // Sanitize methodName: remove potential `(params)` suffix from LLM.
        methodName = stripParams(methodName);
        if (methodName.isBlank()) {
            throw new IllegalArgumentException("Cannot get call graph: method name is empty");
        }

        var graph = getAnalyzer().getCallgraphTo(methodName, 5);
        String result = AnalyzerUtil.formatCallGraph(graph, methodName, true);
        if (result.isEmpty()) {
            return "No callers found of method: " + methodName;
        }
        return result;
    }

    @Tool(value = """
    Returns the call graph to a depth of 5 showing which methods are called by the given method and one line of source code for each invocation.
    Use this to understand how a method's logic flows to other parts of the codebase.
    """)
    public String getCallGraphFrom(
            @P("Fully qualified method name (package name, class name, method name) to find callees for")
            String methodName
    ) {
        assert getAnalyzer().isCpg() : "Cannot get call graph: CPG analyzer is not available.";
        // Sanitize methodName: remove potential `(params)` suffix from LLM.
        methodName = stripParams(methodName);
        if (methodName.isBlank()) {
            throw new IllegalArgumentException("Cannot get call graph: method name is empty");
        }

        var graph = getAnalyzer().getCallgraphFrom(methodName, 5); // Use correct analyzer method
        String result = AnalyzerUtil.formatCallGraph(graph, methodName, false);
        if (result.isEmpty()) {
            return "No calls out made by method: " + methodName;
        }
        return result;
    }

    // --- Text search tools

    @Tool(value = """
    Returns file names whose text contents match Java regular expression patterns.
    This is slower than searchSymbols but can find references to external dependencies and comment strings.
    """)
    public String searchSubstrings(
            @P("Java-style regex patterns to search for within file contents. Unlike searchSymbols this does not automatically include any implicit anchors or case insensitivity.")
            List<String> patterns,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
            String reasoning
    ) {
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search substrings: patterns list is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for searchSubstrings call");
        }

        logger.debug("Searching file contents for patterns: {}", patterns);

        List<Pattern> compiledPatterns = patterns.stream()
                .filter(p -> !p.isBlank())
                .map(Pattern::compile)
                .toList();

        if (compiledPatterns.isEmpty()) {
            throw new IllegalArgumentException("No valid patterns provided");
        }

        var matchingFilenames = contextManager.getProject().getAllFiles().parallelStream().map(file -> {
                    try {
                        if (!file.isText()) {
                            return null;
                        }
                        String fileContents = file.read(); // Use ProjectFile.read()

                        for (Pattern compiledPattern : compiledPatterns) {
                            if (compiledPattern.matcher(fileContents).find()) {
                                return file;
                            }
                        }
                        return null;
                    } catch (Exception e) {
                        logger.debug("Error processing file {}", file, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .map(ProjectFile::toString)
                .collect(Collectors.toSet());

        if (matchingFilenames.isEmpty()) {
            return "No files found with content matching patterns: " + String.join(", ", patterns);
        }

        var msg = "Files with content matching patterns: " + String.join(", ", matchingFilenames);
        logger.debug(msg);
        return msg;
    }

    @Tool(value = """
    Returns filenames (relative to the project root) that match the given Java regular expression patterns.
    Use this to find configuration files, test data, or source files when you know part of their name.
    """)
    public String searchFilenames(
            @P("Java-style regex patterns to match against filenames.")
            List<String> patterns,
            @P("Explanation of what you're looking for in this request so the summarizer can accurately capture it.")
            String reasoning
    ) {
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Cannot search filenames: patterns list is empty");
        }
        if (reasoning.isBlank()) {
            logger.warn("Missing reasoning for searchFilenames call");
        }

        logger.debug("Searching filenames for patterns: {}", patterns);

        List<Pattern> compiledPatterns = patterns.stream()
                .filter(p -> !p.isBlank())
                .map(Pattern::compile)
                .toList();

        if (compiledPatterns.isEmpty()) {
            throw new IllegalArgumentException("No valid patterns provided");
        }

        var matchingFiles = contextManager.getProject().getAllFiles().stream()
                .map(ProjectFile::toString) // Use relative path from ProjectFile
                .filter(filePath -> {
                    for (Pattern compiledPattern : compiledPatterns) {
                        if (compiledPattern.matcher(filePath).find()) {
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

    @Tool(value = """
    Returns the full contents of the specified files. Use this after searchFilenames or searchSubstrings, or when you need the content of a non-code file.
    This can be expensive for large files.
    """)
    public String getFileContents(
            @P("List of filenames (relative to project root) to retrieve contents for.")
            List<String> filenames
    ) {
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
                var content = file.read();
                if (result.length() > 0) {
                    result.append("\n\n");
                }
                result.append("<file name=\"%s\">\n%s\n</file>".formatted(filename, content));
                anySuccess = true;
            } catch (IOException e) {
                logger.error("Error reading file content for {}: {}", filename, e.getMessage());
                // Continue to next file
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

    // Only includes project files. Is this what we want?
    @Tool(value = """
    Lists files within a specified directory relative to the project root.
    Use '.' for the root directory.
    """)
    public String listFiles(
            @P("Directory path relative to the project root (e.g., '.', 'src/main/java')")
            String directoryPath
    ) {
        if (directoryPath.isBlank()) {
            throw new IllegalArgumentException("Directory path cannot be empty");
        }

        // Normalize path for filtering (remove leading/trailing slashes, handle '.')
        var normalizedPath = Path.of(directoryPath).normalize();

        logger.debug("Listing files for directory path: '{}' (normalized to `{}`)", directoryPath, normalizedPath);

        var files = contextManager.getProject().getAllFiles().stream().parallel()
                .filter(file -> file.getParent().equals(normalizedPath))
                .sorted()
                .map(ProjectFile::toString)
                .collect(Collectors.joining(", "));

        if (files.isEmpty()) {
            return "No files found in directory: " + directoryPath;
        }

        return "Files in " + directoryPath + ": " + files;
    }
}
