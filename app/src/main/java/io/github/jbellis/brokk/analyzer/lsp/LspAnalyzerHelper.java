package io.github.jbellis.brokk.analyzer.lsp;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.lsp.domain.SymbolContext;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A collection of largely pure functions to help navigate the given LSP server in a language-agnostic way.
 */
public final class LspAnalyzerHelper {

    static Logger logger = LoggerFactory.getLogger(LspAnalyzerHelper.class.getName());

    public static final Set<SymbolKind> TYPE_KINDS = Set.of(
            SymbolKind.Class,
            SymbolKind.Interface,
            SymbolKind.Enum,
            SymbolKind.Struct
    );

    public static final Set<SymbolKind> METHOD_KINDS = Set.of(
            SymbolKind.Method,
            SymbolKind.Function,
            SymbolKind.Constructor
    );

    public static final Set<SymbolKind> MODULE_KINDS = Set.of(
            SymbolKind.Module,
            SymbolKind.Namespace,
            SymbolKind.Package
    );

    public static final Set<SymbolKind> FIELD_KINDS = Set.of(
            SymbolKind.Field,
            SymbolKind.Variable
    );

    @NotNull
    public static CodeUnitType codeUnitForSymbolKind(@NotNull SymbolKind symbolKind) {
        if (METHOD_KINDS.contains(symbolKind)) return CodeUnitType.FUNCTION;
        else if (TYPE_KINDS.contains(symbolKind)) return CodeUnitType.CLASS;
        else if (MODULE_KINDS.contains(symbolKind)) return CodeUnitType.MODULE;
        else return CodeUnitType.FIELD;
    }

    /**
     * Helper to extract the URI string from the nested Either type in a WorkspaceSymbol's location.
     */
    @NotNull
    public static String getUriStringFromLocation(@NotNull Either<Location, WorkspaceSymbolLocation> locationEither) {
        return locationEither.isLeft()
                ? locationEither.getLeft().getUri()
                : locationEither.getRight().getUri();
    }

    @NotNull
    public static Optional<String> getSourceForURIAndRange(@NotNull Range range, @NotNull String uriString) {
        return getSourceForUriString(uriString).map(fullSource -> getSourceForRange(fullSource, range));
    }

    @NotNull
    public static Optional<String> getSourceForSymbol(@NotNull WorkspaceSymbol symbol) {
        final String uriString = getUriStringFromLocation(symbol.getLocation());
        return getSourceForUriString(uriString);
    }

    @NotNull
    public static Optional<String> getSourceForUriString(@NotNull String uriString) {
        try {
            final Path filePath = Paths.get(new URI(uriString));
            return Optional.of(Files.readString(filePath));
        } catch (IOException | URISyntaxException e) {
            logger.error("Failed to read source for URI '{}'", uriString, e);
            return Optional.empty();
        }
    }

    @NotNull
    public static Optional<String> getCodeForCallSite(
            boolean isIncoming,
            @NotNull Location originalMethod,
            @NotNull CallHierarchyItem callSite,
            @NotNull List<Range> ranges
    ) {
        try {
            // the ranges describe callsite or originalMethod depending on direction
            final String targetFile = isIncoming ? callSite.getUri() : originalMethod.getUri();
            final Path filePath = Paths.get(new URI(targetFile));
            return Optional.of(Files.readString(filePath))
                    .map(source -> {
                        if (ranges.isEmpty()) {
                            return getSourceForRange(source, callSite.getSelectionRange());
                        } else {
                            return getSourceForRange(source, ranges.getFirst());
                        }
                    });
        } catch (IOException | URISyntaxException e) {
            logger.error("Failed to read source for symbol '{}' at {}", callSite.getName(), callSite.getUri(), e);
            return Optional.empty();
        }
    }

    /**
     * Gets the precise source code for a symbol's definition using its Range.
     *
     * @param symbol The symbol to get the source for.
     * @return An Optional containing the source code snippet, or empty if no range is available.
     */
    @NotNull
    public static Optional<String> getSourceForSymbolDefinition(@NotNull WorkspaceSymbol symbol) {
        if (symbol.getLocation().isLeft()) {
            Location location = symbol.getLocation().getLeft();
            return getSourceForSymbol(symbol).map(fullSource -> getSourceForRange(fullSource, location.getRange()));
        }

        logger.warn("Cannot get source for symbol '{}' because its location has no Range information.", symbol.getName());
        return Optional.empty();
    }

    /**
     * A helper that extracts a block of text from a string based on LSP Range data.
     */
    @NotNull
    public static String getSourceForRange(@NotNull String fileContent, @NotNull Range range) {
        String[] lines = fileContent.split("\\R", -1); // Split by any line break
        int startLine = range.getStart().getLine();
        int endLine = range.getEnd().getLine();
        int startChar = range.getStart().getCharacter();
        int endChar = range.getEnd().getCharacter();

        if (startLine == endLine) {
            return lines[startLine].substring(startChar, endChar);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(lines[startLine].substring(startChar)).append(System.lineSeparator());

        for (int i = startLine + 1; i < endLine; i++) {
            sb.append(lines[i]).append(System.lineSeparator());
        }

        if (endChar > 0) {
            sb.append(lines[endLine], 0, endChar);
        }

        return sb.toString();
    }

    /**
     * Converts a WorkspaceSymbol into its more detailed DocumentSymbol counterpart.
     *
     * @param workspaceSymbol The WorkspaceSymbol to resolve.
     * @return A CompletableFuture that will resolve to the corresponding DocumentSymbol, if found.
     */
    public static @NotNull CompletableFuture<Optional<DocumentSymbol>> getDocumentSymbol(@NotNull WorkspaceSymbol workspaceSymbol, @NotNull LspServer sharedServer) {
        if (workspaceSymbol.getLocation().isRight()) {
            // We need a full Location with a Range, not just a URI.
            return CompletableFuture.completedFuture(Optional.empty());
        }
        final Location location = workspaceSymbol.getLocation().getLeft();
        final Path filePath = Paths.get(URI.create(location.getUri()));
        final Position position = location.getRange().getStart();

        // Get the full symbol tree for the file.
        return getSymbolsInFile(sharedServer, filePath)
                .thenApply(eithers -> eithers
                        .stream()
                        .flatMap(either -> {
                            if (either.isLeft()) {
                                return Optional.<DocumentSymbol>empty().stream();
                            }
                            // Search the tree for the symbol at the given position.
                            return findSymbolInTree(Collections.singletonList(either.getRight()), position).stream();
                        }).findFirst());
    }

    /**
     * Given a workspace symbol for a method, returns the snippet of the declaration line.
     *
     * @param symbol the method workspace symbol.
     * @return the method declaration as a single line with no comments.
     */
    public static @NotNull Optional<String> getSourceFromMethodSymbol(@NotNull WorkspaceSymbol symbol, @NotNull LspServer sharedServer) {
        return getDocumentSymbol(symbol, sharedServer).join().flatMap(docSymbol ->
                getSourceForSymbol(symbol).map(source ->
                        getSourceFromMethodRange(source, docSymbol.getRange())
                )
        );
    }

    public static @NotNull String getSourceFromMethodRange(@NotNull String fullSource, @NotNull Range range) {
        return getSourceForRange(fullSource, range)
                .replaceAll("(?s)/\\*.*?\\*/", "") // Remove block comments
                .replaceAll("//.*", "") // Remove line comments
                .lines().map(String::trim)
                .collect(Collectors.joining(" "))
                .trim();
    }

    /**
     * Gets a list of all symbols defined within a specific file.
     *
     * @param filePath The path to the file to analyze.
     * @return A CompletableFuture that will resolve with the server's response, which is an
     * 'Either' containing a list of SymbolInformation (older format) or DocumentSymbol (newer, hierarchical format).
     */
    @NotNull
    public static CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> getSymbolsInFile(
            @NotNull LspServer sharedServer,
            @NotNull Path filePath
    ) {
        logger.debug("Querying for document symbols in {}", filePath);
        return sharedServer.query(server -> {
            final var params = new DocumentSymbolParams(
                    new TextDocumentIdentifier(filePath.toUri().toString())
            );
            return server.getTextDocumentService().documentSymbol(params).join();
        });
    }

    /**
     * Gets a flat list of WorkspaceSymbols from a specific file.
     */
    @NotNull
    public static CompletableFuture<List<WorkspaceSymbol>> getWorkspaceSymbolsInFile(
            @NotNull LspServer sharedServer,
            @NotNull Path filePath
    ) {
        // Call the original method to get the server's response
        final var uri = filePath.toUri().toString();
        return getSymbolsInFile(sharedServer, filePath).thenApply(eitherWorkspaceSymbols -> {
                    final var documentSymbols = eitherWorkspaceSymbols
                            .stream()
                            .flatMap(either -> Optional.ofNullable(either.getRight()).stream())
                            .toList();

                    if (documentSymbols.isEmpty()) {
                        return Collections.emptyList();
                    } else if (documentSymbols.getFirst().getKind() == SymbolKind.Package && documentSymbols.size() > 1) {
                        // Packages/Namespaces are sometimes siblings to the first class, instead of containers
                        final var head = documentSymbols.getFirst();
                        final var tail = documentSymbols.subList(1, documentSymbols.size());
                        return tail
                                .stream()
                                .flatMap(documentSymbol -> documentSymbolsToWorkspaceSymbols(documentSymbol, uri, null, head.getKind(), head.getName()).stream())
                                .toList();
                    } else {
                        return documentSymbols
                                .stream()
                                .flatMap(documentSymbol -> documentSymbolsToWorkspaceSymbols(documentSymbol, uri, null, null, null).stream())
                                .toList();
                    }
                }
        );
    }

    @NotNull
    public static WorkspaceSymbol documentToWorkspaceSymbol(
            @NotNull DocumentSymbol documentSymbol,
            @NotNull String uriString
    ) {
        return new WorkspaceSymbol(
                documentSymbol.getName(),
                documentSymbol.getKind(),
                Either.forLeft(new Location(uriString, documentSymbol.getRange()))
        );
    }

    /**
     * Helper to recursively convert a tree of DocumentSymbols to a flat list of WorkspaceSymbols.
     * (This is the same helper from our previous conversation).
     */
    @NotNull
    public static List<WorkspaceSymbol> documentSymbolsToWorkspaceSymbols(
            @NotNull DocumentSymbol topLevelSymbol,
            @NotNull String uriString,
            @Nullable String initialParentName,
            @Nullable SymbolKind initialParentKind,
            @Nullable String initialParentModule
    ) {
        List<WorkspaceSymbol> flatList = new ArrayList<>();
        collectWorkspaceSymbols(Collections.singletonList(topLevelSymbol), uriString, initialParentName, initialParentKind, initialParentModule, flatList);
        return flatList;
    }

    private static void collectWorkspaceSymbols(
            @NotNull List<DocumentSymbol> symbols,
            @NotNull String uriString,
            @Nullable String parentName,
            @Nullable SymbolKind parentKind,
            @Nullable String parentModule,
            @NotNull List<WorkspaceSymbol> collection
    ) {
        for (final DocumentSymbol docSymbol : symbols) {
            final String module;
            if (parentModule != null) {
                if (parentName != null && MODULE_KINDS.contains(parentKind)) {
                    module = parentModule + "." + parentName;
                } else {
                    module = parentModule;
                }
            } else {
                module = null;
            }

            final String name;
            if (parentKind != null && parentName != null && TYPE_KINDS.contains(parentKind)) {
                // Nested types should be delimited like this for the CodeUnit
                name = parentName + "." + docSymbol.getName();
            } else {
                name = docSymbol.getName();
            }

            final var workspaceSymbol = new WorkspaceSymbol(
                    name,
                    docSymbol.getKind(),
                    Either.forLeft(new Location(uriString, docSymbol.getRange())),
                    Optional.ofNullable(module).orElse("")
            );
            collection.add(workspaceSymbol);

            if (docSymbol.getChildren() != null && !docSymbol.getChildren().isEmpty()) {
                collectWorkspaceSymbols(docSymbol.getChildren(), uriString, workspaceSymbol.getName(), docSymbol.getKind(), module, collection);
            }
        }
    }

    /**
     * Recursively searches a tree of DocumentSymbol objects for a symbol with a specific name and kind.
     */
    @NotNull
    public static List<DocumentSymbol> findSymbolsInTree(
            @NotNull List<DocumentSymbol> symbols,
            @NotNull String name,
            @NotNull Set<SymbolKind> kinds,
            @NotNull Function<String, String> resolveMethodName
    ) {
        return symbols.stream().flatMap(symbol -> {
                    if (resolveMethodName.apply(symbol.getName()).equals(name) && kinds.contains(symbol.getKind())) {
                        return Stream.of(symbol);
                    } else {
                        if (symbol.getChildren() != null && !symbol.getChildren().isEmpty()) {
                            return findSymbolsInTree(symbol.getChildren(), name, kinds, resolveMethodName).stream();
                        } else {
                            return Stream.empty();
                        }
                    }
                }
        ).toList();
    }

    /**
     * Recursively searches a tree of DocumentSymbol objects for symbols.
     */
    @NotNull
    public static List<DocumentSymbol> findAllSymbolsInTree(@NotNull List<DocumentSymbol> symbols) {
        return symbols.stream().flatMap(symbol -> {
            if (symbol.getChildren() != null && !symbol.getChildren().isEmpty()) {
                return findAllSymbolsInTree(symbol.getChildren()).stream();
            } else {
                return Stream.of(symbol);
            }
        }).toList();
    }

    /**
     * Recursively searches a tree of DocumentSymbols to find the one whose name (`selectionRange`)
     * contains the given position. This version returns the full DocumentSymbol.
     */
    public static @NotNull Optional<DocumentSymbol> findSymbolInTree(List<DocumentSymbol> symbols, Position position) {
        for (DocumentSymbol symbol : symbols) {
            if (isPositionInRange(symbol.getSelectionRange(), position)) {
                return Optional.of(symbol);
            }
            if (symbol.getChildren() != null && !symbol.getChildren().isEmpty()) {
                final Optional<DocumentSymbol> found = findSymbolInTree(symbol.getChildren(), position);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Takes a location (e.g., from a workspace/symbol search) and finds the full range
     * for the symbol at that position by performing a more detailed documentSymbol search.
     *
     * @param location The location of the symbol's name.
     * @return A CompletableFuture resolving to the full range of the symbol's definition.
     */
    public static CompletableFuture<Optional<Range>> getFullSymbolRange(@NotNull LspServer sharedServer, @NotNull Location location) {
        final Path filePath = Paths.get(URI.create(location.getUri()));
        final Position position = location.getRange().getStart();

        return getSymbolsInFile(sharedServer, filePath).thenApply(eithers ->
                eithers.stream()
                        .map(either -> either.isRight() ?
                                findRangeInTree(Collections.singletonList(either.getRight()), position) : Optional.<Range>empty())
                        .flatMap(Optional::stream)
                        .findFirst()

        );
    }

    /**
     * Recursively searches a tree of DocumentSymbols to find the one whose name (`selectionRange`)
     * contains the given position, and returns its full body range (`range`).
     */
    private static Optional<Range> findRangeInTree(List<DocumentSymbol> symbols, Position position) {
        for (DocumentSymbol symbol : symbols) {
            // Check if the symbol's name range contains the position
            if (isPositionInRange(symbol.getSelectionRange(), position)) {
                return Optional.of(symbol.getRange()); // Return the full block range
            }
            // Recurse into children
            if (symbol.getChildren() != null && !symbol.getChildren().isEmpty()) {
                Optional<Range> found = findRangeInTree(symbol.getChildren(), position);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    public static boolean isPositionInRange(Range range, Position position) {
        if (position.getLine() == range.getStart().getLine() && position.getCharacter() <= range.getEnd().getCharacter()) {
            return true;
        }
        if (position.getLine() < range.getStart().getLine() || position.getLine() > range.getEnd().getLine()) {
            return false;
        } else if (position.getLine() == range.getStart().getLine() && position.getCharacter() < range.getStart().getCharacter()) {
            return false;
        } else {
            return position.getLine() != range.getEnd().getLine() || position.getCharacter() <= range.getEnd().getCharacter();
        }
    }

    /**
     * Finds symbols within this analyzer's specific workspace using the modern WorkspaceSymbol type.
     *
     * @param symbolName   The name of the symbol to search for.
     * @param workspace    The name of the current workspace.
     * @param sharedServer The LSP server to query.
     * @return A CompletableFuture that will be completed with a list of symbols
     * found only within this instance's project path.
     */
    public static @NotNull CompletableFuture<List<? extends WorkspaceSymbol>> findSymbolsInWorkspace(
            @NotNull String symbolName,
            @NotNull String workspace,
            @NotNull LspServer sharedServer) {
        final var allSymbolsFuture =
                sharedServer.query(server ->
                        server.getWorkspaceService().symbol(new WorkspaceSymbolParams(symbolName))
                );

        return allSymbolsFuture.thenApply(futureEither ->
                futureEither.thenApply(symbols -> eitherSymbolsToWorkspaceSymbols(symbols, workspace)).join()
        );
    }

    private static @NotNull List<WorkspaceSymbol> eitherSymbolsToWorkspaceSymbols(
            @NotNull Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> eitherSymbols,
            @Nullable String workspaceFilter
    ) {
        if (eitherSymbols.isLeft()) {
            // Case 1: Server sent the DEPRECATED type. Convert to the new type and filter.
            return eitherSymbols.getLeft().stream()
                    .map(LspAnalyzerHelper::toWorkspaceSymbol)
                    .filter(symbol -> Optional.ofNullable(workspaceFilter).map(s -> LspAnalyzerHelper.getUriStringFromLocation(symbol.getLocation()).startsWith(s)).orElse(true))
                    .collect(Collectors.toList());
        } else if (eitherSymbols.isRight()) {
            // Case 2: Server sent the MODERN type. Just filter.
            return eitherSymbols.getRight().stream()
                    .filter(symbol -> Optional.ofNullable(workspaceFilter).map(s -> LspAnalyzerHelper.getUriStringFromLocation(symbol.getLocation()).startsWith(s)).orElse(true))
                    .collect(Collectors.toList());
        } else {
            return new ArrayList<>();
        }
    }

    /**
     * Helper to convert a deprecated SymbolInformation object to the modern WorkspaceSymbol.
     */
    @NotNull
    @SuppressWarnings("deprecation")
    public static WorkspaceSymbol toWorkspaceSymbol(@NotNull SymbolInformation info) {
        var ws = new WorkspaceSymbol(info.getName(), info.getKind(), Either.forLeft(info.getLocation()));
        ws.setContainerName(info.getContainerName());
        return ws;
    }

    /**
     * Using the language-specific method full name resolution, will determine the name of the given method's "container" name and parent method name.
     *
     * @param methodFullName    the fully qualified method name. No special constructor handling is done.
     * @param resolveMethodName the method name cleaning function.
     * @return a qualified method record if successful, an empty result otherwise.
     */
    @NotNull
    public static Optional<SymbolContext> determineMethodName(
            @NotNull String methodFullName, @NotNull Function<String, String> resolveMethodName
    ) {
        final String cleanedName = resolveMethodName.apply(methodFullName);
        final int lastIndex = cleanedName.lastIndexOf('.');
        if (lastIndex != -1) {
            final String className = cleanedName.substring(0, lastIndex);
            final String methodName = cleanedName.substring(lastIndex + 1);
            return Optional.of(new SymbolContext(className, methodName));
        } else {
            return Optional.empty();
        }
    }

    public static boolean simpleOrFullMatch(@NotNull WorkspaceSymbol symbol, @NotNull String simpleOrFullName) {
        final String symbolFullName = symbol.getContainerName() == null || symbol.getContainerName().isEmpty() ?
                symbol.getName()
                : symbol.getContainerName() + "." + symbol.getName();
        return symbol.getName().equals(simpleOrFullName) || symbolFullName.equals(simpleOrFullName);
    }

    public static boolean simpleOrFullMatch(@NotNull WorkspaceSymbol symbol, @NotNull String simpleOrFullName, Function<String, String> resolveMethodName) {
        final var name = resolveMethodName.apply(symbol.getName());
        final String symbolFullName = symbol.getContainerName() == null || symbol.getContainerName().isEmpty() ?
                name
                : symbol.getContainerName() + "." + name;
        return name.equals(simpleOrFullName) || symbolFullName.equals(simpleOrFullName);
    }

    public static boolean simpleOrFullMatch(@NotNull CodeUnit unit, @NotNull String simpleOrFullName) {
        return unit.shortName().equals(simpleOrFullName) || unit.fqName().equals(simpleOrFullName);
    }

    /**
     * Finds a type (class, interface, enum) by its simple or fully qualified name within the workspace.
     * This gives a fuzzy match.
     *
     * @param containerName The exact, case-sensitive simple name of the package or type to find.
     * @return A CompletableFuture that will be completed with a list of matching symbols.
     */
    @NotNull
    public static CompletableFuture<List<WorkspaceSymbol>> findTypesInWorkspace(
            @NotNull String containerName,
            @NotNull String workspace,
            @NotNull LspServer sharedServer
    ) {
        return findTypesInWorkspace(containerName, workspace, sharedServer, true);
    }

    /**
     * Finds a type (class, interface, enum) by its exact simple or fully qualified name within the workspace.
     *
     * @param containerName The exact, case-sensitive simple name of the package or type to find.
     * @param fuzzySearch   Whether to consider "close enough" matches or exact ones.
     * @return A CompletableFuture that will be completed with a list of matching symbols.
     */
    @NotNull
    public static CompletableFuture<List<WorkspaceSymbol>> findTypesInWorkspace(
            @NotNull String containerName,
            @NotNull String workspace,
            @NotNull LspServer sharedServer,
            boolean fuzzySearch
    ) {
        if (fuzzySearch) {
            return typesByNameFuzzy(containerName, workspace, sharedServer)
                    .thenApply(symbols -> symbols.collect(Collectors.toList()));
        } else {
            return typesByName(containerName, workspace, sharedServer)
                    .thenApply(symbols ->
                            symbols.filter(symbol ->
                                    simpleOrFullMatch(symbol, containerName)
                            ).collect(Collectors.toList())
                    );
        }
    }

    @NotNull
    public static CompletableFuture<Stream<? extends WorkspaceSymbol>> typesByName(
            @NotNull String name, @NotNull String workspace, @NotNull LspServer sharedServer) {
        return typesByNameFuzzy(name, workspace, sharedServer)
                .thenApply(symbols ->
                        symbols.filter(symbol -> simpleOrFullMatch(symbol, name))
                );
    }

    @NotNull
    public static CompletableFuture<Stream<? extends WorkspaceSymbol>> typesByNameFuzzy(
            @NotNull String name, @NotNull String workspace, @NotNull LspServer sharedServer) {
        return LspAnalyzerHelper.findSymbolsInWorkspace(name, workspace, sharedServer).thenApply(symbols ->
                symbols.stream()
                        .filter(symbol -> TYPE_KINDS.contains(symbol.getKind()))
        );
    }

    /**
     * Searches for the method name using its parent container.
     *
     * @param containerName the parent container, this can be a type or package.
     * @param methodName    the method name to match.
     * @return all matching methods for the given parameters.
     */
    public static CompletableFuture<List<WorkspaceSymbol>> findMethodSymbol(
            @NotNull String containerName,
            @NotNull String methodName,
            @NotNull String workspace,
            @NotNull LspServer sharedServer,
            @NotNull Function<String, String> resolveMethodName
    ) {
        return LspAnalyzerHelper.findTypesInWorkspace(containerName, workspace, sharedServer, false)
                .thenCompose(classLocations -> {
                    if (classLocations.isEmpty()) {
                        return CompletableFuture.completedFuture(Collections.emptyList());
                    }
                    return CompletableFuture.completedFuture(
                            findMethodSymbol(classLocations, methodName, sharedServer, resolveMethodName));
                });
    }

    private static List<WorkspaceSymbol> findMethodSymbol(
            @NotNull List<WorkspaceSymbol> classLocations,
            @NotNull String methodName,
            @NotNull LspServer sharedServer,
            @NotNull Function<String, String> resolveMethodName
    ) {
        return classLocations.stream().flatMap(classLocation -> {
            final String uriString = LspAnalyzerHelper.getUriStringFromLocation(classLocation.getLocation());
            final Path filePath = Paths.get(URI.create(uriString));
            return LspAnalyzerHelper.getSymbolsInFile(sharedServer, filePath).thenApply(fileSymbols ->
                    fileSymbols.stream().map(fileSymbolsEither -> {
                        if (fileSymbolsEither.isRight()) {
                            return LspAnalyzerHelper.findSymbolsInTree(
                                            Collections.singletonList(fileSymbolsEither.getRight()),
                                            methodName,
                                            METHOD_KINDS,
                                            resolveMethodName
                                    )
                                    .stream()
                                    .map(documentSymbol -> {
                                                final var workspaceSymbol = LspAnalyzerHelper.documentToWorkspaceSymbol(documentSymbol, uriString);
                                                final var containerFullName = classLocation.getContainerName() == null || classLocation.getContainerName().isEmpty() ?
                                                        classLocation.getName() : classLocation.getContainerName() + "." + classLocation.getName();
                                                workspaceSymbol.setContainerName(containerFullName);
                                                return workspaceSymbol;
                                            }
                                    )
                                    .toList();
                        } else {
                            // Find the symbol and map it to a new Location object with a precise range
                            return new ArrayList<WorkspaceSymbol>();
                        }
                    }).flatMap(Collection::stream).toList()
            ).join().stream();
        }).toList();
    }

    /**
     * Gets all symbols that the server has indexed for this specific workspace.
     *
     * @return A CompletableFuture that will be completed with a list of all indexed symbols.
     */
    public static CompletableFuture<List<? extends WorkspaceSymbol>> getAllWorkspaceSymbols(@NotNull String workspace, @NotNull LspServer sharedServer) {
        // An empty query string "" tells the server to return all symbols. 
        // Relies on indexes, so shouldn't be too expensive
        return findSymbolsInWorkspace("*", workspace, sharedServer);
    }

    /**
     * Given a symbol's location, finds usages.
     *
     * @param symbolLocation the symbol's location.
     * @param sharedServer   the LSP server.
     * @return locations of other usages.
     */
    public static CompletableFuture<List<? extends Location>> findUsages(
            @NotNull Location symbolLocation,
            @NotNull LspServer sharedServer
    ) {
        return sharedServer.query(server -> {
            final var params = new ReferenceParams();
            params.setTextDocument(new TextDocumentIdentifier(symbolLocation.getUri()));
            params.setPosition(symbolLocation.getRange().getStart());

            // We typically don't want the original declaration included in the usage list.
            params.setContext(new ReferenceContext(false));

            return server.getTextDocumentService().references(params).join();
        });
    }

    /**
     * Finds all usages of a symbol and resolves them into rich WorkspaceSymbol objects.
     *
     * @param symbolLocation The location of the symbol's definition.
     * @return A CompletableFuture that will resolve with a list of WorkspaceSymbols for each usage.
     */
    public static @NotNull CompletableFuture<List<WorkspaceSymbol>> findUsageSymbols(
            @NotNull Location symbolLocation,
            @NotNull LspServer sharedServer
    ) {
        // Get all the usage locations first.
        return findUsages(symbolLocation, sharedServer).thenCompose(locations -> {
            // For each location, start an async task to find the specific symbol at that location.
            final List<CompletableFuture<Optional<WorkspaceSymbol>>> symbolFutures = locations.stream()
                    .map(location -> findSymbolAtLocation(location, sharedServer))
                    .toList();

            // Wait for all the individual lookups to complete.
            return CompletableFuture.allOf(symbolFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> symbolFutures.stream()
                            .map(CompletableFuture::join) // Get the result from each future
                            .filter(Optional::isPresent)   // Filter out any that weren't found
                            .map(Optional::get)
                            .collect(Collectors.toList())
                    );
        });
    }

    /**
     * Finds the specific WorkspaceSymbol that occupies a given Location in a file.
     *
     * @param location The location of the usage.
     * @return A CompletableFuture that resolves to the specific WorkspaceSymbol at that location.
     */
    private static @NotNull CompletableFuture<Optional<WorkspaceSymbol>> findSymbolAtLocation(
            @NotNull Location location,
            @NotNull LspServer sharedServer
    ) {
        final var filePath = Paths.get(URI.create(location.getUri()));

        // Get all symbols in the file where the usage occurred.
        return getWorkspaceSymbolsInFile(sharedServer, filePath).thenApply(symbols ->
                // Find the smallest symbol that completely contains the usage's range.
                symbols.stream()
                        .filter(symbol -> isRangeContained(symbol.getLocation().getLeft().getRange(), location.getRange()))
                        .min((s1, s2) -> {
                            // Heuristic to find the most specific (smallest) symbol containing the range
                            Range r1 = s1.getLocation().getLeft().getRange();
                            Range r2 = s2.getLocation().getLeft().getRange();
                            int size1 = (r1.getEnd().getLine() - r1.getStart().getLine());
                            int size2 = (r2.getEnd().getLine() - r2.getStart().getLine());
                            return Integer.compare(size1, size2);
                        })
        );
    }

    /**
     * Helper to check if range B is contained within range A.
     */
    public static boolean isRangeContained(Range a, Range b) {
        final Position aStart = a.getStart();
        final Position aEnd = a.getEnd();
        final Position bStart = b.getStart();
        final Position bEnd = b.getEnd();

        return (aStart.getLine() < bStart.getLine() || (aStart.getLine() == bStart.getLine() && aStart.getCharacter() <= bStart.getCharacter())) &&
                (aEnd.getLine() > bEnd.getLine() || (aEnd.getLine() == bEnd.getLine() && aEnd.getCharacter() >= bEnd.getCharacter()));
    }

    public static @NotNull CompletableFuture<List<WorkspaceSymbol>> getDirectChildren(
            @NotNull CodeUnit codeUnit,
            @NotNull LspServer sharedServer
    ) {
        // We can only find children of container types like classes and modules.
        if (!codeUnit.isClass() || !codeUnit.isModule()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        final Path filePath = codeUnit.source().absPath();

        // Get all symbols in the file.
        return getWorkspaceSymbolsInFile(sharedServer, filePath).thenApply(allSymbols ->
                // Filter the list to find symbols whose container is the class we're interested in.
                allSymbols.stream()
                        .filter(symbol ->
                                codeUnit.shortName().equals(symbol.getContainerName())
                        )
                        .collect(Collectors.toList())
        );
    }


}
