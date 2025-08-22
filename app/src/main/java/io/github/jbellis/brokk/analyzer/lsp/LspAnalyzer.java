package io.github.jbellis.brokk.analyzer.lsp;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.analyzer.lsp.domain.SymbolContext;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.lsp4j.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface LspAnalyzer extends IAnalyzer, AutoCloseable {

    Logger logger = LoggerFactory.getLogger(LspAnalyzer.class);

    @NotNull
    Path getProjectRoot();

    @NotNull
    String getWorkspace();

    @NotNull
    LspServer getServer();

    @NotNull
    default Optional<CountDownLatch> getWorkspaceReadyLatch(String workspace) {
        return this.getServer().getWorkspaceReadyLatch(workspace);
    }

    /** @return the target programming language as per the LSP's setting specs, e.g., "java". */
    @NotNull
    String getLanguage();

    @Override
    default boolean isCpg() {
        return false;
    }

    @Override
    default boolean isEmpty() {
        // If no source files are determined either because none are applicable or analysis failed, this will be
        // empty
        return getSourceFiles().join().isEmpty();
    }

    @SuppressWarnings("unchecked")
    default CompletableFuture<List<Path>> getSourceFiles() {
        // Ask the server for the list of source directories
        getWorkspaceReadyLatch(getWorkspace()).ifPresent(latch -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for workspace to be ready.", e);
            }
        });
        return getServer()
                .query(server -> {
                    ExecuteCommandParams params = new ExecuteCommandParams(
                            "java.project.listSourcePaths",
                            // The command needs the URI of the project to operate on
                            List.of(this.getProjectRoot().toUri().toString()));
                    return server.getWorkspaceService().executeCommand(params).thenApply(result -> {
                        final var responseBody = (Map<String, Object>) result;
                        if (responseBody.containsKey("data")) {
                            return (List<Map<String, String>>) responseBody.get("data");
                        } else {
                            return Collections.<Map<String, String>>emptyList();
                        }
                    });
                })
                .thenApply(spmFuture -> {
                    List<Path> allFiles = new ArrayList<>();
                    // For each source directory returned by the server...
                    spmFuture
                            .thenAccept(sourcePathMaps -> {
                                for (final Map<String, String> sourcePathMap : sourcePathMaps) {
                                    final Path sourceDir = Path.of(sourcePathMap.get("path"));
                                    try (final var walk = Files.walk(sourceDir)) {
                                        walk.filter(p -> !Files.isDirectory(p)
                                                        && p.toString().endsWith(".java"))
                                                .forEach(allFiles::add);
                                    } catch (IOException e) {
                                        logger.error("Failed to walk source directory: {}", sourceDir, e);
                                    }
                                }
                            })
                            .join();

                    return allFiles;
                });
    }

    /** The number of code-related files, considering excluded directories are ignored */
    @Override
    default CodeBaseMetrics getMetrics() {
        final var sourceFiles = getSourceFiles().join();
        return new CodeBaseMetrics(
                sourceFiles.size(), sourceFiles.size() // an approximation to be fast
                );
    }

    /** @return the language-specific configuration options for the LSP. */
    default @NotNull Map<String, Object> getInitializationOptions() {
        return Collections.emptyMap();
    }

    /** Transform method node fullName to a stable "resolved" name (e.g. removing lambda suffixes). */
    @NotNull
    String resolveMethodName(@NotNull String methodName);

    /** Possibly remove package names from a type string, or do other language-specific cleanup. */
    @NotNull
    String sanitizeType(@NotNull String typeName);

    @Override
    default @NotNull IAnalyzer update(@NotNull Set<ProjectFile> changedFiles) {
        getServer().update(changedFiles);
        logger.debug("Sent didChangeWatchedFiles notification for {} files.", changedFiles.size());
        return this;
    }

    @Override
    default @NotNull IAnalyzer update() {
        getServer().refreshWorkspace().join();
        return this;
    }

    default boolean isClassInProject(String className) {
        return !LspAnalyzerHelper.findTypesInWorkspace(className, getWorkspace(), getServer())
                .join()
                .isEmpty();
    }

    /**
     * The server's search is optimized for speed and interactive use, not complex regex patterns. It supports:
     *
     * <ul>
     *   <li>Fuzzy/Substring Matching: A query like "Buffer" will match StringBuffer and BufferedReader.
     *   <li>CamelCase Matching: A query like "RBE" will match ReadOnlyBufferException.
     *   <li>* (asterisk) matches any sequence of characters.
     *   <li>? (question mark) matches any single character.
     * </ul>
     *
     * <p>Thus, we need to "sanitize" more complex operations otherwise these will be interpreted literally by the
     * server.
     *
     * @param pattern the given search pattern.
     * @return any matching {@link CodeUnit}s.
     */
    @Override
    default @NotNull List<CodeUnit> searchDefinitions(@Nullable String pattern) {
        // fixme: We need to handle fields somehow, do we scan workspace code if this lookup is empty?
        if (pattern == null || pattern.isEmpty()) {
            return Collections.emptyList();
        }

        // A set of complex regex characters to be replaced by a broad wildcard.
        final String cleanedPattern = sanitizeRegexToSimpleWildcard(pattern);
        final String finalPattern = ensureSurroundingWildcards(cleanedPattern);

        return searchDefinitionsImpl(pattern, finalPattern, null);
    }

    @Override
    default @NotNull Optional<CodeUnit> getDefinition(@Nullable String fqName) {
        return getDefinitionsInWorkspace(fqName).stream()
                .map(this::codeUnitForWorkspaceSymbol)
                .findFirst();
    }

    private List<? extends WorkspaceSymbol> getDefinitionsInWorkspace(@Nullable String fqName) {
        if (fqName == null) {
            return Collections.emptyList();
        } else {
            final var resolvedFqName = resolveMethodName(fqName); // Works for types too
            final var workspace = getWorkspace();
            final var server = getServer();
            // launch both requests at the same time
            final CompletableFuture<List<? extends WorkspaceSymbol>> exactMatchFuture =
                    LspAnalyzerHelper.findSymbolsInWorkspace(resolvedFqName, workspace, server)
                            .thenApply(workspaceSymbols -> workspaceSymbols.stream()
                                    .filter(symbol -> LspAnalyzerHelper.simpleOrFullMatch(symbol, resolvedFqName))
                                    .toList());
            final Stream<CompletableFuture<List<? extends WorkspaceSymbol>>> fallbackFuture =
                    LspAnalyzerHelper.determineMethodName(resolvedFqName, this::resolveMethodName).stream()
                            .map(qualifiedMethod -> {
                                final var methodName = qualifiedMethod.methodName();
                                final var containerName = qualifiedMethod.containerFullName();
                                return LspAnalyzerHelper.findMethodSymbol(
                                                containerName, methodName, workspace, server, this::resolveMethodName)
                                        .thenApply(workspaceSymbols -> workspaceSymbols.stream()
                                                .filter(symbol -> LspAnalyzerHelper.simpleOrFullMatch(
                                                        symbol, fqName, this::resolveMethodName))
                                                .toList());
                            });

            final var exactMatch = exactMatchFuture.join();
            if (!exactMatch.isEmpty()) {
                fallbackFuture.forEach(x -> x.cancel(true));
                return exactMatch;
            } else {
                return fallbackFuture.flatMap(x -> x.join().stream()).toList();
            }
        }
    }

    /**
     * Add surrounding wildcards so that name matches will work by ignoring expected container prefixes and signatures.
     *
     * @param pattern a pattern which may or may not be surrounded by wildcard (*) characters.
     * @return the given pattern surrounded by wildcard characters.
     */
    private static @NotNull String ensureSurroundingWildcards(@NotNull String pattern) {
        if (pattern.startsWith("*") && pattern.endsWith("*")) {
            return pattern;
        } else {
            // Assume there may be wildcards on either side instead of creating a super long if-else chain, and strip
            // these to avoid adding duplicates.
            final var tmpPattern = StringUtils.stripStart(StringUtils.stripEnd(pattern, "*"), "*");
            return "*" + tmpPattern + "*";
        }
    }

    /**
     * Sanitizes a Java regular expression into a simpler wildcard pattern compatible with the LSP symbol search.
     *
     * @param pattern The input regular expression.
     * @return A simplified string with wildcards (* and ?).
     */
    private static @NotNull String sanitizeRegexToSimpleWildcard(@NotNull String pattern) {
        final String complexRegexChars = "[\\|\\[\\]\\(\\)\\{\\}\\^\\$\\+\\\\]";

        // Handle the most common patterns directly.
        // Replace any remaining complex regex syntax with a broad wildcard.
        // Collapse multiple consecutive wildcards into a single one.
        return pattern
                // Handle the most common patterns directly.
                .replace(".*", "*")
                .replace(".", "?")
                // Replace any remaining complex regex syntax with a broad wildcard.
                .replaceAll(complexRegexChars, "*")
                // Collapse multiple consecutive wildcards into a single one.
                .replaceAll("\\*+", "*")
                .replaceAll("\\?+", "?");
    }

    @Override
    default @NotNull List<CodeUnit> searchDefinitionsImpl(
            @NotNull String originalPattern, @Nullable String fallbackPattern, @Nullable Pattern compiledPattern) {
        final CompletableFuture<List<? extends WorkspaceSymbol>> searchRequest;
        if (fallbackPattern != null) {
            searchRequest = LspAnalyzerHelper.findSymbolsInWorkspace(fallbackPattern, getWorkspace(), getServer());
        } else if (compiledPattern != null) {
            searchRequest = LspAnalyzerHelper.findSymbolsInWorkspace(originalPattern, getWorkspace(), getServer());
        } else {
            searchRequest = CompletableFuture.completedFuture(Collections.emptyList());
        }

        return searchRequest
                .thenApply(symbols ->
                        symbols.stream().map(this::codeUnitForWorkspaceSymbol).toList())
                .join();
    }

    @Override
    default @NotNull List<CodeUnit> getUses(@Nullable String fqName) {
        if (fqName == null || fqName.isEmpty()) {
            final var reason = "Symbol '" + fqName + "' not found as a method, field, or class";
            throw new IllegalArgumentException(reason);
        }

        final var definitions = getDefinitionsInWorkspace(fqName);

        if (definitions.isEmpty()) {
            final var reason = "Symbol '" + fqName + "' (resolved: '" + resolveMethodName(fqName)
                    + "') not found as a method, field, or class";
            throw new IllegalArgumentException(reason);
        }

        final var usagesFutures = definitions.stream()
                .flatMap(symbol -> {
                    if (symbol.getLocation().isLeft())
                        return Optional.of(symbol.getLocation().getLeft()).stream();
                    else return Optional.<Location>empty().stream();
                })
                .map(location -> LspAnalyzerHelper.findUsageSymbols(location, getServer())
                        .thenApply(usages -> usages.stream().map(this::codeUnitForWorkspaceSymbol)))
                .toList();
        return CompletableFuture.allOf(usagesFutures.toArray(new CompletableFuture[0]))
                .thenApply(v ->
                        usagesFutures.stream().flatMap(CompletableFuture::join).toList())
                .join();
    }

    /**
     * Locates the source file and line range for the given fully-qualified method name. The {@code paramNames} list
     * contains the *parameter variable names* (not types). If there is only a single match, or exactly one match with
     * matching param names, return it. Otherwise, throw {@code SymbolNotFoundException} or
     * {@code SymbolAmbiguousException}.
     *
     * @param fqMethodName the fully qualified method name.
     * @param paramNames the parameter names to differentiate the function from overloaded variants.
     * @return the function's location.
     * @throws SymbolNotFoundException if no function could be found with the given parameters.
     */
    @Override
    default @NotNull FunctionLocation getFunctionLocation(
            @NotNull String fqMethodName, @NotNull List<String> paramNames) {
        final var methodMatches = getDefinitionsInWorkspace(fqMethodName).stream()
                .filter(symbol -> LspAnalyzerHelper.METHOD_KINDS.contains(symbol.getKind()))
                .toList();

        final var matches = new HashSet<FunctionLocation>();

        for (final WorkspaceSymbol overload : methodMatches) {
            // getRight won't give us the Range object
            if (!overload.getLocation().isLeft()) {
                continue;
            }
            // For each overload, get its full signature from the source.
            final var uri = LspAnalyzerHelper.getUriStringFromLocation(overload.getLocation());
            final var maybeSnippet = LspAnalyzerHelper.getSourceFromMethodSymbol(overload, getServer());
            if (maybeSnippet.isPresent()) {
                final var signatureSnippet = maybeSnippet.get();
                // Extract the parameter list string.
                final int openParen = signatureSnippet.indexOf('(');
                final int closeParen = signatureSnippet.indexOf(')');

                if (openParen != -1 && closeParen > openParen) {
                    final String paramListString = signatureSnippet.substring(openParen + 1, closeParen);

                    // Parse the names from the string.
                    final List<String> actualParamNames = getParameterNamesFromString(paramListString);

                    // If the parsed names match the expected names, we've found our method.
                    if (methodMatches.size() == 1 || actualParamNames.equals(paramNames)) {
                        final Path filePath = Paths.get(URI.create(uri));
                        final Range range = overload.getLocation().getLeft().getRange();
                        final var projectFile = new ProjectFile(
                                getProjectRoot(), getProjectRoot().relativize(filePath));
                        matches.add(new FunctionLocation(
                                projectFile,
                                range.getStart().getLine(),
                                range.getEnd().getLine(),
                                signatureSnippet));
                    }
                }
            }
        }
        if (matches.size() > 1) {
            // if more than one match is found, this is ambiguous
            final var reason = matches.size() + " methods found in " + fqMethodName
                    + " matching provided parameter names [" + String.join(", ", paramNames) + "]";
            throw new SymbolAmbiguousException(reason);
        } else if (matches.isEmpty()) {
            // if nothing found, bail
            final var reason = "No methods found in " + fqMethodName + " matching provided parameter names ["
                    + String.join(", ", paramNames) + "]";
            throw new SymbolNotFoundException(reason);
        } else {
            return matches.iterator().next();
        }
    }

    /**
     * A helper to parse a list of parameter names from a declaration string.
     *
     * @param parameterString e.g., "String input, int otherInput"
     * @return A list of names, e.g., ["input", "otherInput"]
     */
    private List<String> getParameterNamesFromString(String parameterString) {
        if (parameterString == null || parameterString.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(parameterString.split(","))
                .map(String::strip)
                .map(param -> {
                    List<String> parts = Splitter.on(Pattern.compile("\\s+")).splitToList(param);
                    // The last part is the name (e.g., from "String input")
                    return parts.getLast();
                })
                .collect(Collectors.toList());
    }

    @Override
    default @Nullable String getClassSource(@NotNull String classFullName) {
        final var futureTypeSymbols =
                LspAnalyzerHelper.findTypesInWorkspace(classFullName, getWorkspace(), getServer(), false);
        final var exactMatch = getClassSource(futureTypeSymbols);

        if (exactMatch == null) {
            // fallback to the whole file, if any partial matches are present
            return futureTypeSymbols.join().stream()
                    .map(LspAnalyzerHelper::getSourceForSymbol)
                    .flatMap(Optional::stream)
                    .distinct()
                    .sorted()
                    .findFirst()
                    .orElseGet(() -> {
                        // fallback to the whole file, if any partial matches for parent container are present
                        final var classCleanedName = classFullName.replace('$', '.');
                        if (classCleanedName.contains(".")) {
                            final var parentContainer =
                                    classCleanedName.substring(0, classCleanedName.lastIndexOf('.'));
                            final var matches =
                                    LspAnalyzerHelper.findTypesInWorkspace(parentContainer, getWorkspace(), getServer())
                                            .join()
                                            .stream()
                                            .toList();
                            final var fallbackExactMatches = matches.stream()
                                    .filter(s -> LspAnalyzerHelper.simpleOrFullMatch(s, classCleanedName))
                                    .map(LspAnalyzerHelper::getSourceForSymbol)
                                    .flatMap(Optional::stream)
                                    .distinct()
                                    .sorted()
                                    .findFirst();
                            if (fallbackExactMatches.isPresent()) {
                                return fallbackExactMatches.get();
                            } else {
                                return matches.stream()
                                        .filter(s -> s.getContainerName().endsWith(parentContainer))
                                        .map(LspAnalyzerHelper::getSourceForSymbol)
                                        .flatMap(Optional::stream)
                                        .distinct()
                                        .sorted()
                                        .findFirst()
                                        .orElse(null);
                            }
                        } else {
                            return null;
                        }
                    });
        } else {
            return exactMatch;
        }
    }

    private @Nullable String getClassSource(CompletableFuture<List<WorkspaceSymbol>> typeSymbols) {
        return typeSymbols
                .thenApply(symbols -> symbols.stream()
                        .map(symbol -> {
                            final var eitherLocation = symbol.getLocation();
                            if (eitherLocation.isLeft()) {
                                final Location location = eitherLocation.getLeft();
                                return LspAnalyzerHelper.getFullSymbolRange(getServer(), location).join().stream()
                                        .flatMap(range ->
                                                LspAnalyzerHelper.getSourceForURIAndRange(range, location.getUri())
                                                        .stream())
                                        .findFirst();
                            } else {
                                return Optional.<String>empty();
                            }
                        })
                        .flatMap(Optional::stream))
                .join()
                .findFirst()
                .orElse(null);
    }

    @Override
    default @NotNull Optional<String> getMethodSource(@NotNull String fqName) {
        return LspAnalyzerHelper.determineMethodName(fqName, this::resolveMethodName)
                .map(qualifiedMethodInfo -> LspAnalyzerHelper.findMethodSymbol(
                                qualifiedMethodInfo.containerFullName(),
                                qualifiedMethodInfo.methodName(),
                                getWorkspace(),
                                getServer(),
                                this::resolveMethodName)
                        .thenApply(maybeSymbol -> maybeSymbol.stream()
                                .map(LspAnalyzerHelper::getSourceForSymbolDefinition)
                                .flatMap(Optional::stream)
                                .distinct()
                                .collect(Collectors.joining("\n\n")))
                        .join())
                .filter(x -> !x.isBlank());
    }

    @Override
    default @NotNull List<CodeUnit> getAllDeclarations() {
        return LspAnalyzerHelper.getAllWorkspaceSymbols(getWorkspace(), getServer())
                .thenApply(symbols -> symbols.stream()
                        .filter(s -> LspAnalyzerHelper.TYPE_KINDS.contains(s.getKind()))
                        .map(this::codeUnitForWorkspaceSymbol))
                .join()
                .toList();
    }

    @Override
    default @NotNull Set<CodeUnit> getDeclarationsInFile(ProjectFile file) {
        return LspAnalyzerHelper.getWorkspaceSymbolsInFile(getServer(), file.absPath())
                .thenApply(symbols -> symbols.stream().map(this::codeUnitForWorkspaceSymbol))
                .join()
                .collect(Collectors.toSet());
    }

    @Override
    default @NotNull Optional<ProjectFile> getFileFor(@NotNull String fqName) {
        return LspAnalyzerHelper.typesByName(fqName, getWorkspace(), getServer())
                .thenApply(symbols -> symbols.map(symbol -> {
                    final var symbolUri = URI.create(LspAnalyzerHelper.getUriStringFromLocation(symbol.getLocation()));
                    return new ProjectFile(
                            getProjectRoot(),
                            getProjectRoot().relativize(Path.of(symbolUri).toAbsolutePath()));
                }))
                .join()
                .findFirst();
    }

    @Override
    default @NotNull Map<String, List<CallSite>> getCallgraphTo(@NotNull String methodName, int depth) {
        final Optional<SymbolContext> methodNameInfo =
                LspAnalyzerHelper.determineMethodName(methodName, this::resolveMethodName);
        if (methodNameInfo.isPresent()) {
            final String className = methodNameInfo.get().containerFullName();
            final String name = methodNameInfo.get().methodName();
            final Map<String, List<CallSite>> callGraph = new HashMap<>();

            final String key = className + "." + name;
            final var functionSymbols = LspAnalyzerHelper.findMethodSymbol(
                            className, name, getWorkspace(), getServer(), this::resolveMethodName)
                    .join();
            functionSymbols.stream()
                    .flatMap(x -> Optional.ofNullable(x.getLocation().getLeft()).stream())
                    .forEach(originMethod -> LspCallGraphHelper.getCallers(getServer(), originMethod)
                            .join()
                            .forEach(
                                    incomingCall -> callGraphEntry(originMethod, callGraph, key, incomingCall, depth)));
            return callGraph;
        } else {
            logger.warn("Method name not found: {}", methodName);
            return new HashMap<>();
        }
    }

    private Map<String, List<CallSite>> getCallgraphTo(CallSite callSite, int depth) {
        if (depth > 0) {
            return getCallgraphTo(callSite.target().fqName(), depth - 1);
        } else {
            return new HashMap<>();
        }
    }

    @Override
    default @NotNull Map<String, List<CallSite>> getCallgraphFrom(@NotNull String methodName, int depth) {
        final Optional<SymbolContext> methodNameInfo =
                LspAnalyzerHelper.determineMethodName(methodName, this::resolveMethodName);
        if (methodNameInfo.isPresent()) {
            final String className = methodNameInfo.get().containerFullName();
            final String name = methodNameInfo.get().methodName();
            final Map<String, List<CallSite>> callGraph = new HashMap<>();

            final String key = className + "." + name;
            final var functionSymbols = LspAnalyzerHelper.findMethodSymbol(
                            className, name, getWorkspace(), getServer(), this::resolveMethodName)
                    .join();
            functionSymbols.stream()
                    .flatMap(x -> Optional.ofNullable(x.getLocation().getLeft()).stream())
                    .forEach(originMethod -> LspCallGraphHelper.getCallees(getServer(), originMethod)
                            .join()
                            .forEach(
                                    outgoingCall -> callGraphEntry(originMethod, callGraph, key, outgoingCall, depth)));
            return callGraph;
        } else {
            logger.warn("Method name not found: {}", methodName);
            return new HashMap<>();
        }
    }

    private Map<String, List<CallSite>> getCallgraphFrom(CallSite callSite, int depth) {
        if (depth > 0) {
            return getCallgraphFrom(callSite.target().fqName(), depth - 1);
        } else {
            return new HashMap<>();
        }
    }

    private void callGraphEntry(
            Location originMethod, Map<String, List<CallSite>> callGraph, String key, Object someCall, int depth) {
        if (someCall instanceof CallHierarchyIncomingCall incomingCall) {
            final CallSite newCallSite = registerCallItem(
                    key, true, originMethod, incomingCall.getFrom(), incomingCall.getFromRanges(), callGraph);
            // Continue search, and add any new entries
            getCallgraphTo(newCallSite, depth - 1).forEach((k, v) -> {
                final var nestedCallSites = callGraph.getOrDefault(k, new ArrayList<>());
                nestedCallSites.addAll(v);
                callGraph.put(k, nestedCallSites);
            });
        } else if (someCall instanceof CallHierarchyOutgoingCall outgoingCall) {
            final CallSite newCallSite = registerCallItem(
                    key, false, originMethod, outgoingCall.getTo(), outgoingCall.getFromRanges(), callGraph);
            // Continue search, and add any new entries
            getCallgraphFrom(newCallSite, depth - 1).forEach((k, v) -> {
                final var nestedCallSites = callGraph.getOrDefault(k, new ArrayList<>());
                nestedCallSites.addAll(v);
                callGraph.put(k, nestedCallSites);
            });
        }
    }

    private CallSite registerCallItem(
            String key,
            boolean isIncoming,
            Location originMethod,
            CallHierarchyItem callItem,
            List<Range> ranges,
            Map<String, List<CallSite>> callGraph) {
        final var uri = Path.of(URI.create(callItem.getUri()));
        final var projectFile =
                new ProjectFile(this.getProjectRoot(), this.getProjectRoot().relativize(uri));
        final var containerInfo = callItem.getDetail() == null
                ? ""
                : callItem.getDetail(); // TODO: Not sure if null means empty or external
        final var cu = new CodeUnit(
                projectFile,
                LspAnalyzerHelper.codeUnitForSymbolKind(callItem.getKind()),
                containerInfo,
                resolveMethodName(callItem.getName()));
        final var sourceLine = LspAnalyzerHelper.getCodeForCallSite(isIncoming, originMethod, callItem, ranges)
                .orElse(callItem.getName() + "(...)");
        final var callSites = callGraph.getOrDefault(key, new ArrayList<>());
        final var newCallSite = new CallSite(cu, sourceLine);
        callSites.add(newCallSite);
        callGraph.put(key, callSites);
        return newCallSite;
    }

    default CodeUnit codeUnitForWorkspaceSymbol(WorkspaceSymbol symbol) {
        final var uri = Path.of(URI.create(symbol.getLocation().getLeft().getUri()));
        final var projectFile =
                new ProjectFile(this.getProjectRoot(), this.getProjectRoot().relativize(uri));
        final var codeUnitKind = LspAnalyzerHelper.codeUnitForSymbolKind(symbol.getKind());
        final var containerName = Optional.ofNullable(symbol.getContainerName()).orElse("");
        final String name;
        if (LspAnalyzerHelper.METHOD_KINDS.contains(symbol.getKind())) {
            final var tmpName = resolveMethodName(symbol.getName());
            if (isAnonymousClass(symbol.getKind(), symbol.getName())
                    && symbol.getLocation().isLeft()) {
                name = getAnonymousName(symbol.getLocation().getLeft()).orElse(tmpName);
            } else {
                name = tmpName;
            }
        } else {
            name = symbol.getName();
        }

        return new CodeUnit(projectFile, codeUnitKind, containerName, name);
    }

    /**
     * Heuristically checks if the symbol refers to an anonymous class.
     *
     * @param kind the symbol kind.
     * @param name the given symbol's name.
     * @return true if this is likely an anonymous class, false if otherwise.
     */
    boolean isAnonymousClass(@NotNull SymbolKind kind, @NotNull String name);

    /**
     * Generates a more informative, bytecode-style name for an anonymous class or lambda symbol.
     *
     * @param lambdaLocation The location of the anonymous class or lambda symbol.
     * @return The generated name (e.g., "MyClass.myMethod$anon$42:20") if the location is valid.
     */
    default Optional<String> getAnonymousName(Location lambdaLocation) {
        final Path filePath = Paths.get(URI.create(lambdaLocation.getUri()));
        logger.debug("Determining name for anonymous structure at {}", lambdaLocation);
        return LspAnalyzerHelper.getSymbolsInFile(getServer(), filePath)
                .thenApply(eithers -> eithers.stream()
                        .flatMap(either -> {
                            if (either.isLeft()) {
                                return Optional.<String>empty().stream();
                            }
                            // Find the parent class and method for the anonymous symbol's location
                            return findParentContext(
                                    Collections.singletonList(either.getRight()), lambdaLocation.getRange())
                                    .map(context -> String.format(
                                            // Construct the name using Class.Method$anon$LineNumber:ColumnNumber
                                            "%s.%s$anon$%d:%d",
                                            context.containerFullName(),
                                            context.methodName(),
                                            lambdaLocation.getRange().getStart().getLine(),
                                            lambdaLocation.getRange().getStart().getCharacter()))
                                    .stream();
                        })
                        .findFirst())
                .join();
    }

    /** Recursively searches a symbol tree to find the containing class and method for a given range. */
    private Optional<SymbolContext> findParentContext(List<DocumentSymbol> symbols, Range targetRange) {
        final Deque<DocumentSymbol> contextStack = new ArrayDeque<>();
        return findParentContextRecursive(symbols, targetRange, contextStack);
    }

    private Optional<SymbolContext> findParentContextRecursive(
            List<DocumentSymbol> symbols, Range targetRange, Deque<DocumentSymbol> contextStack) {
        for (final DocumentSymbol symbol : symbols) {
            if (LspAnalyzerHelper.isRangeContained(symbol.getRange(), targetRange)) {
                contextStack.push(symbol);
                if (symbol.getChildren() != null && !symbol.getChildren().isEmpty()) {
                    final Optional<SymbolContext> found =
                            findParentContextRecursive(symbol.getChildren(), targetRange, contextStack);
                    if (found.isPresent()) {
                        return found;
                    }
                }
                // We've reached the deepest containing symbol. Now build the context.
                String className = null;
                String methodName = null;
                for (final DocumentSymbol s : contextStack) {
                    if (className == null && LspAnalyzerHelper.TYPE_KINDS.contains(s.getKind())) {
                        if (isAnonymousClass(s.getKind(), s.getName())) {
                            methodName = null; // invalidate method name, keep traversing up
                        } else {
                            className = resolveMethodName(s.getName());
                        }
                    }
                    if (methodName == null && LspAnalyzerHelper.METHOD_KINDS.contains(s.getKind())) {
                        methodName = resolveMethodName(s.getName());
                    }
                }
                contextStack.pop();
                return (className != null && methodName != null)
                        ? Optional.of(new SymbolContext(className, methodName))
                        : Optional.empty();
            }
        }
        contextStack.poll(); // Backtrack
        return Optional.empty();
    }

    @Override
    default @NotNull List<CodeUnit> getMembersInClass(@NotNull String fqClass) {
        return getFileFor(fqClass).map(this::getDeclarationsInFile).stream()
                .flatMap(Set::stream)
                .filter(codeUnit -> !codeUnit.isClass())
                .sorted()
                .toList();
    }

    @Override
    default @NotNull List<CodeUnit> directChildren(@NotNull CodeUnit cu) {
        return LspAnalyzerHelper.getDirectChildren(cu, getServer())
                .thenApply(children -> children.stream()
                        .map(this::codeUnitForWorkspaceSymbol)
                        .sorted()
                        .toList())
                .join();
    }
}
